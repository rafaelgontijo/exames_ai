package br.com.exames.inferencelocal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.exames.domain.inference.LocalWeightsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ModelDownloadPhase {
    IDLE,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    READY,
    FAILED_INTEGRITY,
    FAILED_SPACE,
    FAILED_NETWORK,
    CANCELLED,
}

data class ModelDownloadProgress(
    val phase: ModelDownloadPhase = ModelDownloadPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = LocalTextInference.MODEL_BYTES,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L && (phase == ModelDownloadPhase.DOWNLOADING || phase == ModelDownloadPhase.VERIFYING)) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * Pure JVM-testable atomic downloader for pinned Gemma 4 E2B weights.
 * Streams directly into a private `.partial` file, computes incremental SHA-256 + exact byte count,
 * syncs to disk, and promotes atomically only after full cryptographic verification.
 */
internal class VerifiedModelStreamDownloader(
    private val directory: File,
    private val spec: ModelSpec,
    private val space: () -> Long = { directory.usableSpace },
    private val atomicRename: (File, File) -> Unit = { src, dst ->
        Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    fun download(
        openStream: () -> InputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): File = synchronized(downloadLock) {
        checkCancelled()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ModelPreparationException(ModelPreparationFailure.STORAGE_UNAVAILABLE)
        }
        RandomAccessFile(File(directory, ".prepare.lock"), "rw").use { lock ->
            lock.channel.use { channel ->
                channel.lock().use {
                    checkCancelled()
                    val target = File(directory, MODEL_FILENAME)
                    cleanupPartials()
                    if (isValid(target, checkCancelled)) {
                        onProgress(spec.size, spec.size)
                        return target
                    }
                    if (space() < spec.size + AtomicModelPreparer.RESERVE_BYTES) {
                        throw ModelPreparationException(ModelPreparationFailure.INSUFFICIENT_SPACE)
                    }
                    val temporary = File(directory, ".gemma4-${UUID.randomUUID()}.partial")
                    try {
                        openStream().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val digest = MessageDigest.getInstance("SHA-256")
                                val buffer = ByteArray(1024 * 1024)
                                var totalRead = 0L
                                var lastReported = 0L
                                while (true) {
                                    checkCancelled()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    totalRead += read
                                    if (totalRead > spec.size) {
                                        throw ModelPreparationException(ModelPreparationFailure.CORRUPT_ASSET)
                                    }
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    if (totalRead - lastReported >= PROGRESS_STEP_BYTES || totalRead == spec.size) {
                                        lastReported = totalRead
                                        onProgress(totalRead, spec.size)
                                    }
                                }
                                if (totalRead != spec.size || hex(digest.digest()) != spec.sha256) {
                                    throw ModelPreparationException(ModelPreparationFailure.CORRUPT_ASSET)
                                }
                                output.fd.sync()
                            }
                        }
                        checkCancelled()
                        atomicRename(temporary, target)
                        onProgress(spec.size, spec.size)
                        return target
                    } catch (cancelled: CancellationException) {
                        temporary.delete()
                        throw cancelled
                    } catch (known: ModelPreparationException) {
                        temporary.delete()
                        throw known
                    } catch (io: IOException) {
                        temporary.delete()
                        throw io
                    } catch (_: Exception) {
                        temporary.delete()
                        throw ModelPreparationException(ModelPreparationFailure.STORAGE_UNAVAILABLE)
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
            }
        }
    }

    fun deleteLocalWeights(): Boolean = synchronized(downloadLock) {
        cleanupPartials()
        val target = File(directory, MODEL_FILENAME)
        if (!target.exists()) return true
        target.delete()
    }

    fun inspectState(isDownloading: Boolean = false, hasBundledAsset: Boolean = false): LocalWeightsState {
        val target = File(directory, MODEL_FILENAME)
        if (target.isFile && target.length() == spec.size) return LocalWeightsState.READY
        if (isDownloading) return LocalWeightsState.DOWNLOADING
        if (target.isFile && target.length() != spec.size) return LocalWeightsState.CORRUPT
        if (hasBundledAsset) {
            return if (space() >= spec.size + AtomicModelPreparer.RESERVE_BYTES) {
                LocalWeightsState.READY
            } else {
                LocalWeightsState.INSUFFICIENT_SPACE
            }
        }
        if (directory.exists() || directory.parentFile?.exists() == true) {
            if (space() < spec.size + AtomicModelPreparer.RESERVE_BYTES) {
                return LocalWeightsState.INSUFFICIENT_SPACE
            }
        }
        return LocalWeightsState.NOT_DOWNLOADED
    }

    private fun cleanupPartials() {
        directory.listFiles()
            ?.filter { it.name.startsWith(".gemma4-") && it.name.endsWith(".partial") }
            ?.forEach { it.delete() }
    }

    private fun isValid(file: File, checkCancelled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            hex(digest.digest()) == spec.sha256
        } catch (_: IOException) {
            false
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL_FILENAME = "gemma4.litertlm"
        private const val PROGRESS_STEP_BYTES = 2L * 1024 * 1024
        private val downloadLock = Any()
    }
}

/** Strict TLS-only HTTPS downloader with zero HTTP disk cache. */
internal class OkHttpModelDownloader(
    private val directory: File,
    private val spec: ModelSpec = ModelSpec(LocalTextInference.MODEL_BYTES, LocalTextInference.MODEL_SHA256),
    private val allowCleartextForLocalTests: Boolean = false,
    private val space: () -> Long = { directory.usableSpace },
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (!allowCleartextForLocalTests) {
                connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            }
        }
        .build()

    private val streamDownloader = VerifiedModelStreamDownloader(directory, spec, space)

    suspend fun download(
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (!allowCleartextForLocalTests && !url.startsWith("https://")) {
            throw IOException("HTTPS_REQUIRED")
        }
        val caller = currentCoroutineContext()
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP_${response.code}")
                }
                val body = response.body ?: throw IOException("EMPTY_BODY")
                streamDownloader.download(
                    openStream = { body.byteStream() },
                    onProgress = onProgress,
                    checkCancelled = {
                        try {
                            caller.ensureActive()
                        } catch (cancelled: CancellationException) {
                            call.cancel()
                            throw cancelled
                        }
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        }
    }
}

/** Manages on-demand private storage state, background WorkManager downloads, and local removal. */
class LocalModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, "models")
    private val spec = ModelSpec(LocalTextInference.MODEL_BYTES, LocalTextInference.MODEL_SHA256)
    private val core = VerifiedModelStreamDownloader(directory, spec)

    fun hasBundledAsset(): Boolean = try {
        appContext.assets.openFd("models/gemma4.litertlm").use { true }
    } catch (_: IOException) {
        try {
            appContext.assets.open("models/gemma4.litertlm").use { true }
        } catch (_: IOException) {
            false
        }
    }

    fun inspectState(isDownloading: Boolean = false): LocalWeightsState =
        core.inspectState(isDownloading = isDownloading, hasBundledAsset = hasBundledAsset())

    suspend fun deleteLocalWeights(): Boolean = withContext(Dispatchers.IO) {
        core.deleteLocalWeights()
    }

    fun observeDownloadProgress(): Flow<ModelDownloadProgress> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { infos ->
                val info = infos.firstOrNull()
                when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadProgress(
                        phase = ModelDownloadPhase.QUEUED,
                        downloadedBytes = 0L,
                        totalBytes = spec.size,
                    )
                    WorkInfo.State.RUNNING -> ModelDownloadProgress(
                        phase = ModelDownloadPhase.DOWNLOADING,
                        downloadedBytes = info.progress.getLong(KEY_DOWNLOADED_BYTES, 0L),
                        totalBytes = info.progress.getLong(KEY_TOTAL_BYTES, spec.size),
                    )
                    WorkInfo.State.SUCCEEDED -> ModelDownloadProgress(
                        phase = ModelDownloadPhase.READY,
                        downloadedBytes = spec.size,
                        totalBytes = spec.size,
                    )
                    WorkInfo.State.CANCELLED -> ModelDownloadProgress(
                        phase = ModelDownloadPhase.CANCELLED,
                        downloadedBytes = 0L,
                        totalBytes = spec.size,
                    )
                    WorkInfo.State.FAILED -> {
                        val code = info.outputData.getString(KEY_ERROR_CODE)
                        val phase = when (code) {
                            ModelPreparationFailure.CORRUPT_ASSET.name -> ModelDownloadPhase.FAILED_INTEGRITY
                            ModelPreparationFailure.INSUFFICIENT_SPACE.name -> ModelDownloadPhase.FAILED_SPACE
                            else -> ModelDownloadPhase.FAILED_NETWORK
                        }
                        ModelDownloadProgress(phase = phase, downloadedBytes = 0L, totalBytes = spec.size)
                    }
                    null -> {
                        val installed = inspectState(isDownloading = false) == LocalWeightsState.READY
                        ModelDownloadProgress(
                            phase = if (installed) ModelDownloadPhase.READY else ModelDownloadPhase.IDLE,
                            downloadedBytes = if (installed) spec.size else 0L,
                            totalBytes = spec.size,
                        )
                    }
                }
            }

    fun enqueueDownload(downloadUrl: String, wifiOnly: Boolean) {
        if (downloadUrl.isBlank()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(KEY_DOWNLOAD_URL, downloadUrl).build())
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "gemma4-e2b-on-demand-download"
        internal const val KEY_DOWNLOAD_URL = "download_url"
        internal const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        internal const val KEY_TOTAL_BYTES = "total_bytes"
        internal const val KEY_ERROR_CODE = "error_code"
    }
}

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(LocalModelStore.KEY_DOWNLOAD_URL)?.trim().orEmpty()
        if (url.isEmpty()) {
            return Result.failure(Data.Builder().putString(LocalModelStore.KEY_ERROR_CODE, "MISSING_URL").build())
        }
        val directory = File(applicationContext.noBackupFilesDir, "models")
        val downloader = OkHttpModelDownloader(directory)
        return try {
            downloader.download(url) { downloaded, total ->
                setProgressAsync(
                    Data.Builder()
                        .putLong(LocalModelStore.KEY_DOWNLOADED_BYTES, downloaded)
                        .putLong(LocalModelStore.KEY_TOTAL_BYTES, total)
                        .build(),
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (prep: ModelPreparationException) {
            Result.failure(Data.Builder().putString(LocalModelStore.KEY_ERROR_CODE, prep.reason.name).build())
        } catch (_: IOException) {
            Result.failure(Data.Builder().putString(LocalModelStore.KEY_ERROR_CODE, "NETWORK_ERROR").build())
        }
    }
}
