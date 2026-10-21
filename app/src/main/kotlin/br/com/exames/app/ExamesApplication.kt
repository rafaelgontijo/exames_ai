package br.com.exames.app

import android.app.Application
import br.com.exames.datalocal.DeletionWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ExamesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeletionWorker.schedule(this)
    }
}
