package br.com.exames.datalocal

import android.content.Context
import br.com.exames.datadocuments.SafDocumentReader
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Periodic reconciliation also recovers a crash between DB commit and immediate scheduling. */
class DeletionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var db: ProtectedDatabase? = null
        try {
            val factory = ProtectedDatabaseFactory(applicationContext)
            val database = factory.open()
            db = database
            val store = EncryptedArtifactStore(applicationContext)
            ProtectedDocumentImporter(applicationContext,
                SafDocumentReader(applicationContext.contentResolver), store,
                ProtectedValidationRepository(factory)).reconcile()
            ProtectedDeletionCleanup(factory, store, AndroidRunWorkScheduler(applicationContext)).run()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        } finally { db?.close() }
    }
    companion object {
        fun requestCleanup(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("artifact-cleanup-now",
                ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DeletionWorker>().build())
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("artifact-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DeletionWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
