package br.com.exames.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import br.com.exames.coreui.screens.ExecutionModeUi
import br.com.exames.coreui.screens.PageProgressStatus
import br.com.exames.coreui.screens.PageProgressUi
import br.com.exames.coreui.screens.ProcessingUiState
import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.domain.inference.InferenceExecutionTarget
import br.com.exames.domain.inference.RoutingDecision
import br.com.exames.domain.inference.RoutingReason
import br.com.exames.inferencecloud.CloudAuthTokenProvider
import br.com.exames.inferencecloud.CloudFallbackConfig
import br.com.exames.inferencecloud.CloudFallbackInference
import br.com.exames.inferencelocal.BackgroundNotificationCopy
import br.com.exames.inferencelocal.BackgroundNotifications
import br.com.exames.inferencelocal.LocalTextInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure codec for WorkManager progress/input/output Data.
 * Stores ONLY opaque exam IDs, numeric progress counters, and non-PII flags (never clinical content).
 */
internal object ExamWorkCodec {
    const val UNIQUE_WORK_NAME = "exam-background-extraction"

    const val KEY_EXAM_ID = "exam_id"
    const val KEY_USE_CLOUD = "use_cloud"
    const val KEY_PAGE = "page"
    const val KEY_TOTAL_PAGES = "total_pages"
    const val KEY_BLOCK = "block"
    const val KEY_TOTAL_BLOCKS = "total_blocks"
    const val KEY_COMPLETED_PAGES = "completed_pages"
    const val KEY_COMPLETED_COUNTS = "completed_counts"
    const val KEY_COMPLETED_ISSUES = "completed_issues"
    const val KEY_ERROR_CODE = "error_code"

    fun toInputData(examId: String, useCloud: Boolean): Data = workDataOf(
        KEY_EXAM_ID to examId,
        KEY_USE_CLOUD to useCloud,
    )

    fun toProgressData(
        examId: String,
        useCloud: Boolean,
        page: Int,
        totalPages: Int,
        block: Int,
        totalBlocks: Int,
        finishedPages: Map<Int, PageProgressUi>,
    ): Data {
        val sorted = finishedPages.values.sortedBy { it.page }
        val completedPages = IntArray(sorted.size) { sorted[it].page }
        val completedCounts = IntArray(sorted.size) { sorted[it].verifiedCount }
        val completedIssues = BooleanArray(sorted.size) { sorted[it].status == PageProgressStatus.ISSUE }
        return workDataOf(
            KEY_EXAM_ID to examId,
            KEY_USE_CLOUD to useCloud,
            KEY_PAGE to page,
            KEY_TOTAL_PAGES to totalPages,
            KEY_BLOCK to block,
            KEY_TOTAL_BLOCKS to totalBlocks,
            KEY_COMPLETED_PAGES to completedPages,
            KEY_COMPLETED_COUNTS to completedCounts,
            KEY_COMPLETED_ISSUES to completedIssues,
        )
    }

    fun toSuccessData(examId: String, useCloud: Boolean): Data = workDataOf(
        KEY_EXAM_ID to examId,
        KEY_USE_CLOUD to useCloud,
    )

    fun toFailureData(examId: String, errorCode: String): Data = workDataOf(
        KEY_EXAM_ID to examId,
        KEY_ERROR_CODE to errorCode,
    )

    fun toProcessingUi(data: Data, fallbackUseCloud: Boolean = false): ProcessingUiState {
        val useCloud = data.getBoolean(KEY_USE_CLOUD, fallbackUseCloud)
        val execMode = if (useCloud) ExecutionModeUi.CLOUD_PRIVATE_FALLBACK else ExecutionModeUi.LOCAL_OFFLINE
        val page = data.getInt(KEY_PAGE, 0)
        val totalPages = data.getInt(KEY_TOTAL_PAGES, 0)
        val block = data.getInt(KEY_BLOCK, 0)
        val totalBlocks = data.getInt(KEY_TOTAL_BLOCKS, 0)

        val completedPages = data.getIntArray(KEY_COMPLETED_PAGES) ?: IntArray(0)
        val completedCounts = data.getIntArray(KEY_COMPLETED_COUNTS) ?: IntArray(0)
        val completedIssues = data.getBooleanArray(KEY_COMPLETED_ISSUES) ?: BooleanArray(0)
        val finishedByPage = linkedMapOf<Int, PageProgressUi>()
        for (i in completedPages.indices) {
            val p = completedPages[i]
            val count = completedCounts.getOrElse(i) { 0 }
            val issue = completedIssues.getOrElse(i) { false }
            finishedByPage[p] = PageProgressUi(
                page = p,
                status = if (issue) PageProgressStatus.ISSUE else PageProgressStatus.COMPLETED,
                verifiedCount = count,
            )
        }

        val pageList = if (totalPages > 0) {
            (1..totalPages).map { p ->
                finishedByPage[p] ?: when {
                    p == page -> PageProgressUi(p, PageProgressStatus.IN_PROGRESS)
                    else -> PageProgressUi(p, PageProgressStatus.PENDING)
                }
            }
        } else {
            emptyList()
        }

        val message = when {
            totalPages > 0 && useCloud -> "Extraindo via nuvem privada"
            totalPages > 0 -> "Extraindo com IA"
            else -> "Analisando laudo em PDF"
        }

        return ProcessingUiState(
            message = message,
            page = page,
            totalPages = totalPages,
            block = block,
            totalBlocks = totalBlocks,
            pages = pageList,
            cancellable = true,
            executionMode = execMode,
        )
    }

    fun errorNotice(errorCode: String?): String = when (errorCode) {
        "PDF_PAGE_LIMIT" -> "O PDF excede o limite de páginas."
        "PDF_OPEN_FAILED" -> "Não foi possível abrir o PDF. Verifique se ele exige senha ou está danificado."
        else -> "A extração foi interrompida por uma falha no arquivo ou no armazenamento."
    }
}

internal class ExamBackgroundScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun enqueue(examId: String, useCloud: Boolean) {
        val request = OneTimeWorkRequestBuilder<ExamExtractionWorker>()
            .setInputData(ExamWorkCodec.toInputData(examId, useCloud))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ExamWorkCodec.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(ExamWorkCodec.UNIQUE_WORK_NAME)
    }

    fun observe(): Flow<WorkInfo?> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(ExamWorkCodec.UNIQUE_WORK_NAME)
            .map { infos -> infos.firstOrNull() }
}

/**
 * Foreground-promoted WorkManager worker that extracts an already-accepted encrypted PDF
 * (local or opt-in private cloud) with progress bar + cancel action in the Android notification drawer.
 */
class ExamExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val useCloud = inputData.getBoolean(ExamWorkCodec.KEY_USE_CLOUD, false)
        return createForegroundInfo(useCloud = useCloud, page = 0, totalPages = 0, block = 0, totalBlocks = 0, completedPages = 0)
    }

    override suspend fun doWork(): Result {
        val examId = inputData.getString(ExamWorkCodec.KEY_EXAM_ID).orEmpty()
        val useCloud = inputData.getBoolean(ExamWorkCodec.KEY_USE_CLOUD, false)
        if (!AppRoutes.isOpaqueId(examId)) {
            return Result.failure(ExamWorkCodec.toFailureData(examId, "INVALID_EXAM_ID"))
        }

        BackgroundNotifications.cancel(applicationContext, BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT)
        trySetForeground(useCloud = useCloud, page = 0, totalPages = 0, block = 0, totalBlocks = 0, completedPages = 0)

        val store = EncryptedExamHistoryStore(applicationContext)
        val localInference = LocalTextInference(applicationContext)
        val authTokenProvider = CloudAuthTokenProvider(
            staticBearerToken = BuildConfig.CLOUD_AUTH_TOKEN,
            cognitoRegion = BuildConfig.COGNITO_REGION,
            cognitoClientId = BuildConfig.COGNITO_CLIENT_ID,
            cognitoRefreshToken = BuildConfig.COGNITO_REFRESH_TOKEN,
        )
        val cloudInference = CloudFallbackInference(
            CloudFallbackConfig(
                endpointUrl = BuildConfig.CLOUD_FALLBACK_URL,
                authTokenProvider = authTokenProvider::getToken,
            ),
        )
        val decision = RoutingDecision(
            target = if (useCloud) InferenceExecutionTarget.CLOUD_FALLBACK else InferenceExecutionTarget.LOCAL,
            reason = if (useCloud) RoutingReason.WEIGHTS_NOT_DOWNLOADED else RoutingReason.LOCAL_READY,
            hardwareEligibleForLocal = !useCloud,
            canDownloadLocal = false,
        )
        val runner = ExamRunner(store, HybridTextInference(decision, localInference, cloudInference))
        val finishedPages = linkedMapOf<Int, PageProgressUi>()
        var lastNotifiedAtMs = 0L
        var lastNotifiedKey = ""

        setProgress(
            ExamWorkCodec.toProgressData(
                examId = examId,
                useCloud = useCloud,
                page = 0,
                totalPages = 0,
                block = 0,
                totalBlocks = 0,
                finishedPages = finishedPages,
            ),
        )

        return try {
            val extraction = coroutineScope {
                runner.extract(
                    id = examId,
                    redactPiiForCloud = useCloud,
                    onProgress = { page, totalPages, block, blocks ->
                        val snapshotPages = finishedPages.toMap()
                        val progressData = ExamWorkCodec.toProgressData(
                            examId = examId,
                            useCloud = useCloud,
                            page = page,
                            totalPages = totalPages,
                            block = block,
                            totalBlocks = blocks,
                            finishedPages = snapshotPages,
                        )
                        launch { setProgress(progressData) }
                        val now = System.currentTimeMillis()
                        val key = "$page:$block:${snapshotPages.size}"
                        if (key != lastNotifiedKey && (now - lastNotifiedAtMs >= NOTIFY_THROTTLE_MS || block <= 1)) {
                            lastNotifiedAtMs = now
                            lastNotifiedKey = key
                            val completedCount = snapshotPages.size
                            launch {
                                trySetForeground(
                                    useCloud = useCloud,
                                    page = page,
                                    totalPages = totalPages,
                                    block = block,
                                    totalBlocks = blocks,
                                    completedPages = completedCount,
                                )
                            }
                        }
                    },
                    onPageFinished = { outcome ->
                        val status = if (outcome.issues.isNotEmpty()) PageProgressStatus.ISSUE else PageProgressStatus.COMPLETED
                        finishedPages[outcome.page] = PageProgressUi(outcome.page, status, outcome.observations.size)
                    },
                )
            }
            store.save(examId, extraction)
            BackgroundNotifications.postCompletionAlert(
                context = applicationContext,
                notificationId = BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT,
                title = BackgroundNotificationCopy.examCompletedTitle(),
                text = BackgroundNotificationCopy.examCompletedText(),
                smallIconRes = android.R.drawable.stat_sys_download_done,
                contentIntent = BackgroundNotifications.createAppLaunchIntent(
                    context = applicationContext,
                    requestCode = BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT,
                    examId = examId,
                ),
            )
            Result.success(ExamWorkCodec.toSuccessData(examId, useCloud))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { store.cancelActive(examId) }
                BackgroundNotifications.cancel(applicationContext, BackgroundNotifications.NOTIFICATION_ID_EXAM_PROGRESS)
            }
            throw cancelled
        } catch (failure: Exception) {
            val code = when (failure.message) {
                "PDF_PAGE_LIMIT" -> "PDF_PAGE_LIMIT"
                "PDF_OPEN_FAILED" -> "PDF_OPEN_FAILED"
                else -> "EXTRACTION_FAILED"
            }
            BackgroundNotifications.postCompletionAlert(
                context = applicationContext,
                notificationId = BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT,
                title = BackgroundNotificationCopy.examFailedTitle(),
                text = BackgroundNotificationCopy.examFailedText(),
                smallIconRes = android.R.drawable.stat_notify_error,
                contentIntent = BackgroundNotifications.createAppLaunchIntent(
                    context = applicationContext,
                    requestCode = BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT,
                ),
            )
            Result.failure(ExamWorkCodec.toFailureData(examId, code))
        }
    }

    private suspend fun trySetForeground(
        useCloud: Boolean,
        page: Int,
        totalPages: Int,
        block: Int,
        totalBlocks: Int,
        completedPages: Int,
    ) {
        try {
            setForeground(createForegroundInfo(useCloud, page, totalPages, block, totalBlocks, completedPages))
        } catch (_: IllegalStateException) {
            // Android 12+ ForegroundServiceStartNotAllowedException: continue extraction without crashing.
        } catch (_: SecurityException) {
            // Missing permission edge case: continue extraction gracefully.
        }
    }

    private fun createForegroundInfo(
        useCloud: Boolean,
        page: Int,
        totalPages: Int,
        block: Int,
        totalBlocks: Int,
        completedPages: Int,
    ): ForegroundInfo {
        val percent = BackgroundNotificationCopy.examProgressPercent(page, totalPages, block, totalBlocks, completedPages)
        val notification = BackgroundNotifications.buildProgressNotification(
            context = applicationContext,
            title = BackgroundNotificationCopy.examProgressTitle(useCloud),
            text = BackgroundNotificationCopy.examProgressText(page, totalPages, block, totalBlocks),
            percent = percent,
            smallIconRes = android.R.drawable.stat_notify_sync,
            contentIntent = BackgroundNotifications.createAppLaunchIntent(
                context = applicationContext,
                requestCode = BackgroundNotifications.NOTIFICATION_ID_EXAM_PROGRESS,
                destination = BackgroundNotifications.DESTINATION_PROCESSING,
            ),
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return BackgroundNotifications.buildDataSyncForegroundInfo(
            notificationId = BackgroundNotifications.NOTIFICATION_ID_EXAM_PROGRESS,
            notification = notification,
        )
    }

    private companion object {
        const val NOTIFY_THROTTLE_MS = 500L
    }
}
