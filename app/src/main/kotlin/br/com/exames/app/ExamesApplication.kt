package br.com.exames.app

import android.app.Application
import br.com.exames.datalocal.DeletionWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ExamesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (br.com.exames.inferencelocal.OneShotInferenceService.processName(this) != packageName) return
        DeletionWorker.schedule(this)
        DeletionWorker.requestCleanup(this)
        br.com.exames.datalocal.ProcessingReconciliationWorker.schedule(this)
    }
}
