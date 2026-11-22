package br.com.exames.inferencelocal

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import br.com.exames.domain.validation.RuntimeMeasurements
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Samples only numeric memory counters. Process retirement also retires this sampler. */
internal class RuntimeSampler(context: Context) : AutoCloseable {
    private val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val before=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    private val start=SystemClock.elapsedRealtime()
    private val active=AtomicBoolean(true)
    private val max=AtomicLong(Debug.getPss())
    private val thread=Thread({ while(active.get()) {
        max.accumulateAndGet(Debug.getPss(),Math::max)
        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
    } },"inference-memory-sampler").apply { isDaemon=true;start() }
    fun snapshot(prefill: Int?, decoded: Int?, contextTokens: Int?): RuntimeMeasurements {
        max.accumulateAndGet(Debug.getPss(),Math::max)
        val after=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return RuntimeMeasurements(SystemClock.elapsedRealtime()-start,max.get(),before.availMem,after.availMem,before.totalMem,
            prefill,decoded,contextTokens,processId=android.os.Process.myPid())
    }
    override fun close() { active.set(false);thread.interrupt();thread.join(200) }
}
