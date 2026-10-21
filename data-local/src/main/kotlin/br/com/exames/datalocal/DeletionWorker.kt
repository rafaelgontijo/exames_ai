package br.com.exames.datalocal

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Periodic reconciliation also recovers a crash between DB commit and immediate scheduling. */
class DeletionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var db: ProtectedDatabase? = null
        try {
            val database = ProtectedDatabaseFactory(applicationContext).open()
            db = database
            val store = EncryptedArtifactStore(applicationContext)
            database.records().pendingDeletions().forEach {
                store.deleteDocument(it.documentId)
                database.records().acknowledgeDeletion(it.documentId)
            }
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
