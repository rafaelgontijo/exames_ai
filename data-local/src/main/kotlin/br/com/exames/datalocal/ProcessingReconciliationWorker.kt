package br.com.exames.datalocal

import android.content.Context
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ProcessingReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, ProcessingDependencies::class.java).runControls().reconcile()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork("processing-reconciliation", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProcessingReconciliationWorker>(15, TimeUnit.MINUTES).build())
            manager.enqueueUniqueWork("processing-reconciliation-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProcessingReconciliationWorker>().build())
        }
    }
}
