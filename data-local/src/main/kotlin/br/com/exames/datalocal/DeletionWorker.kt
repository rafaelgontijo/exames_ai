package br.com.exames.datalocal

import android.content.Context
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Periodic reconciliation also recovers a crash between DB commit and immediate scheduling. */
class DeletionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "exames.db").build()
        try {
            val store = EncryptedArtifactStore(applicationContext)
            db.documentDao().pendingDeletions().forEach {
                store.deleteDocument(it.documentId)
                db.documentDao().acknowledgeDeletion(it.documentId)
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        } finally { db.close() }
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("artifact-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DeletionWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
