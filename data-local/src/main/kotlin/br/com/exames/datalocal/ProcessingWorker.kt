package br.com.exames.datalocal

import android.content.Context
import androidx.work.*
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProcessingDependencies {
    fun runProcessor(): ResumeProcessingRun
    fun runControls(): ManageProcessingRuns
}

/** WorkManager stores only an opaque run ID. No URI, clinical text, model prompt or network constraint. */
class ProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var processorOverride: ResumeProcessingRun? = null
    private var controlsOverride: ManageProcessingRuns? = null
    internal constructor(context: Context, params: WorkerParameters, processor: ResumeProcessingRun,
        controls: ManageProcessingRuns? = null) : this(context, params) {
        processorOverride = processor
        controlsOverride = controls
    }
    private fun controls() = controlsOverride ?: EntryPointAccessors.fromApplication(applicationContext, ProcessingDependencies::class.java).runControls()
    override suspend fun doWork(): Result {
        val id = inputData.getString(RUN_ID)?.takeIf { ProcessingRunIds.isOpaque(it) } ?: return Result.failure()
        return try {
            val processor = processorOverride ?: EntryPointAccessors.fromApplication(applicationContext, ProcessingDependencies::class.java).runProcessor()
            when (processor.resume(id)) {
                ResumeRunResult.UNSUPPORTED_CONFIGURATION -> {
                    controls().fail(id, RunFailure.UNSUPPORTED_CONFIGURATION)
                    Result.failure(workDataOf("errorCode" to "RUN_CONFIGURATION_UNSUPPORTED"))
                }
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (runAttemptCount < 2) Result.retry()
              else try {
                  controls().fail(id, RunFailure.WORKER_FAILURE)
                  Result.failure(workDataOf("errorCode" to "WORKER_FAILURE"))
              } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Result.retry() } // Key/DB unavailable: never recreate or discard inaccessible data.
          }
    }
    companion object {
        const val RUN_ID = "runId"
        const val TAG = "processing-runs"
        const val RUN_TAG = "processing-run:"
        fun workName(runId: String): String {
            require(ProcessingRunIds.isOpaque(runId)) { "RUN_ID_INVALID" }
            return "processing-$runId"
        }
        fun request(runId: String): OneTimeWorkRequest {
            workName(runId)
            return OneTimeWorkRequestBuilder<ProcessingWorker>().setInputData(workDataOf(RUN_ID to runId))
                .addTag(TAG).addTag(RUN_TAG + runId).build()
        }
        fun enqueue(context: Context, runId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(workName(runId), ExistingWorkPolicy.KEEP, request(runId))
        }
    }
}
