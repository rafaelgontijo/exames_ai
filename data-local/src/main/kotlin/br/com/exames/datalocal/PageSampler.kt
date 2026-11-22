package br.com.exames.datalocal

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import br.com.exames.domain.validation.PageMeasurements

class PageSampler(context: Context) {
    private val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val before=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    private val start=SystemClock.elapsedRealtime()
    private val pss=Debug.getPss()
    fun finish(): PageMeasurements {
        val after=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return PageMeasurements(SystemClock.elapsedRealtime()-start,pss,Debug.getPss(),before.availMem,after.availMem,before.totalMem)
    }
}
