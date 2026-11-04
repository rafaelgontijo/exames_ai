package br.com.exames.datalocal

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import br.com.exames.domain.usecase.RunWorkScheduler
import br.com.exames.domain.validation.ProcessingRunIds
import kotlinx.coroutines.flow.first

class AndroidRunWorkScheduler(context: Context) : RunWorkScheduler {
    private val manager = WorkManager.getInstance(context)
    override suspend fun enqueue(runId: String) {
        manager.enqueueUniqueWork(ProcessingWorker.workName(runId), androidx.work.ExistingWorkPolicy.KEEP,
            ProcessingWorker.request(runId)).await()
    }
    override suspend fun cancel(runId: String) {
        manager.cancelUniqueWork(ProcessingWorker.workName(runId)).await()
    }
    override suspend fun activeRunIds(): Set<String> = manager.getWorkInfosByTagFlow(ProcessingWorker.TAG).first()
        .filterNot { it.state.isFinished }.flatMap { it.tags }.filter { it.startsWith(ProcessingWorker.RUN_TAG) }
        .map { it.removePrefix(ProcessingWorker.RUN_TAG) }.filter { ProcessingRunIds.isOpaque(it) }.toSet()
}
