package br.com.exames.inferencelocal

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import br.com.exames.domain.inference.*
import java.util.concurrent.atomic.AtomicBoolean

/** Private bound IPC host. One request and fresh engine per process; no clinical disk/log output. */
abstract class OneShotInferenceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean()
    private var nonce: String? = null
    private var caller: Messenger? = null
    private val watchdog = Runnable { terminate() }
    protected abstract fun generate(request: GenerationRequest, generating: () -> Unit): GenerationResult
    private fun childProcess(): Boolean = processName(this)?.let { it != applicationInfo.processName && it.startsWith(packageName + ":") } == true
    private fun terminate() { if (childProcess()) Process.killProcess(Process.myPid()) }
    private fun deadline(millis: Long) { handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, millis) }
    private fun reply(what: Int, data: Bundle = Bundle()) {
        data.putString("nonce", nonce)
        try { caller?.send(Message.obtain(null, what).apply { this.data = data }) } catch (_: RemoteException) { terminate() }
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid() || !childProcess()) return@Handler true
        val id = message.data.getString("nonce") ?: return@Handler true
        if (message.what == InferenceProcessProtocol.HELLO && nonce == null && id.length == 36) {
            nonce = id; caller = message.replyTo
            try { caller?.binder?.linkToDeath({ terminate() }, 0) } catch (_: RemoteException) { terminate() }
            reply(InferenceProcessProtocol.READY, Bundle().apply { putInt("pid", Process.myPid()) })
        } else if (message.what == InferenceProcessProtocol.RUN && id == nonce && started.compareAndSet(false, true)) {
            val request = InferenceProcessProtocol.decodeRequest(message.data)
            if (!InferencePayloadLimits.accepts(request)) {
                reply(InferenceProcessProtocol.FINAL, InferenceProcessProtocol.result(GenerationResult.Failed(InferenceFailure.INPUT_LIMIT), id))
            } else Thread({
                val result = try {
                    generate(request) { deadline(InferenceProcessProtocol.GENERATION_MS); reply(InferenceProcessProtocol.GENERATING) }
                } catch (_: Throwable) { GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE) }
                reply(InferenceProcessProtocol.FINAL, InferenceProcessProtocol.result(result, id))
            }, "local-inference").start()
        }
        true
    })
    override fun onCreate() { super.onCreate(); if (childProcess()) deadline(InferenceProcessProtocol.LOAD_MS) }
    override fun onBind(intent: Intent?): IBinder? = if (childProcess()) messenger.binder else null
    override fun onUnbind(intent: Intent?): Boolean { terminate(); return false }

    companion object {
        fun processName(context: android.content.Context): String? = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
            (context.getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }
}
