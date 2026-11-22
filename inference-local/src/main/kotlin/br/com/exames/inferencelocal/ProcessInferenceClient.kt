package br.com.exames.inferencelocal

import android.app.ActivityManager
import android.content.*
import android.os.*
import br.com.exames.domain.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

internal class ProcessInferenceClient(context: Context,
    private val component: ComponentName = ComponentName(context, InferenceProcessService::class.java),
    private val loadTimeout: Long = InferenceProcessProtocol.LOAD_MS,
    private val generationTimeout: Long = InferenceProcessProtocol.GENERATION_MS) {
    private val context = context.applicationContext
    @Volatile internal var lastProcessId: Int? = null
        private set
    @Volatile internal var generationStarted: Boolean = false
        private set
    init { require(loadTimeout in 1..InferenceProcessProtocol.LOAD_MS && generationTimeout in 1..InferenceProcessProtocol.GENERATION_MS) }
    suspend fun generate(request: GenerationRequest): GenerationResult {
        if (!InferencePayloadLimits.accepts(request)) return GenerationResult.Failed(InferenceFailure.INPUT_LIMIT)
        if (quarantined) return GenerationResult.Failed(InferenceFailure.PROCESS_UNAVAILABLE)
        if (!gate.tryLock()) return GenerationResult.Failed(InferenceFailure.BUSY)
        lastProcessId = null; generationStarted = false
        val call = Call(request)
        var retired = false
        val result = try {
            val loaded = withTimeoutOrNull(loadTimeout) {
                call.bind(); call.ready.await(); call.send()
                select<LoadResult> {
                    call.generating.onAwait { LoadResult(null) }
                    call.final.onAwait { LoadResult(it) }
                }
            }
            when {
                loaded == null -> GenerationResult.Failed(InferenceFailure.LOAD_TIMEOUT)
                loaded.terminal != null -> loaded.terminal
                else -> withTimeoutOrNull(generationTimeout) { call.final.await() }
                    ?: GenerationResult.Failed(InferenceFailure.GENERATION_TIMEOUT)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { GenerationResult.Failed(InferenceFailure.PROCESS_UNAVAILABLE) }
        finally {
            withContext(NonCancellable) {
                try { retired = call.retire() }
                catch (_: Exception) { retired = false }
                finally {
                    if (!retired) quarantined = true
                    gate.unlock()
                }
            }
        }
        return if (retired) result else GenerationResult.Failed(InferenceFailure.PROCESS_UNAVAILABLE)
    }
    private fun ownChildPid(): Int? {
        if (component.packageName != context.packageName) return null
        val service = context.packageManager.getServiceInfo(component, 0)
        if (service.exported || !service.processName.startsWith(context.packageName + ":")) return null
        return context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().firstOrNull {
            it.uid == Process.myUid() && it.pid != Process.myPid() && it.processName == service.processName
        }?.pid
    }

    private data class LoadResult(val terminal: GenerationResult?)
    private inner class Call(private val request: GenerationRequest) : ServiceConnection {
        val ready = CompletableDeferred<Unit>()
        val generating = CompletableDeferred<Unit>()
        val final = CompletableDeferred<GenerationResult>()
        private val dead = CompletableDeferred<Unit>()
        private val nonce = UUID.randomUUID().toString()
        private var bound = false
        private var disposed = false
        private var binder: IBinder? = null
        private var remote: Messenger? = null
        private var pid: Int? = null
        private val death = IBinder.DeathRecipient {
            dead.complete(Unit); final.complete(GenerationResult.Failed(InferenceFailure.PROCESS_DIED)); ready.complete(Unit)
        }
        private val handler = Handler(Looper.getMainLooper()) { message ->
            if (disposed || message.sendingUid != Process.myUid() || message.data.getString("nonce") != nonce) return@Handler true
            when (message.what) {
                InferenceProcessProtocol.READY -> {
                    val id = message.data.getInt("pid", -1)
                    if (id > 0 && id != Process.myPid()) { pid = id; lastProcessId = id; ready.complete(Unit) }
                    else { final.complete(GenerationResult.Failed(InferenceFailure.PROCESS_UNAVAILABLE)); ready.complete(Unit) }
                }
                InferenceProcessProtocol.GENERATING -> { generationStarted = true; generating.complete(Unit) }
                InferenceProcessProtocol.FINAL -> final.complete(InferenceProcessProtocol.decodeResult(message.data))
            }
            true
        }
        private val receiver = Messenger(handler)
        suspend fun bind() = withContext(Dispatchers.Main.immediate) {
            bound = context.bindService(Intent().setComponent(component), this@Call, Context.BIND_AUTO_CREATE)
            check(bound)
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (disposed) return
            binder = service; remote = Messenger(service)
            try {
                service.linkToDeath(death, 0)
                remote!!.send(Message.obtain(null, InferenceProcessProtocol.HELLO).apply {
                    data = Bundle().apply { putString("nonce", nonce) }; replyTo = receiver
                })
            } catch (_: RemoteException) { death.binderDied() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            final.complete(GenerationResult.Failed(InferenceFailure.PROCESS_DIED)); ready.complete(Unit)
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) {
            final.complete(GenerationResult.Failed(InferenceFailure.PROCESS_UNAVAILABLE)); ready.complete(Unit)
        }
        suspend fun send() = withContext(Dispatchers.Main.immediate) {
            if (!final.isCompleted && pid != null) remote!!.send(Message.obtain(null, InferenceProcessProtocol.RUN).apply {
                data = InferenceProcessProtocol.request(request, nonce); replyTo = receiver
            })
        }
        suspend fun retire(): Boolean {
            val earlyChild = withContext(Dispatchers.Main.immediate) {
                disposed = true
                handler.removeCallbacksAndMessages(null)
                try {
                    val startupPid = if (bound && pid == null) ownChildPid() else null
                    if (!dead.isCompleted) (pid ?: startupPid)?.let(Process::killProcess)
                    startupPid
                } finally {
                    if (bound) try { context.unbindService(this@Call) } catch (_: IllegalArgumentException) { }
                }
            }
            val retired = withTimeoutOrNull(InferenceProcessProtocol.REAP_MS) {
                if (earlyChild != null && binder == null) {
                    while (ownChildPid() == earlyChild) delay(25)
                } else if (binder?.isBinderAlive == true) {
                    dead.await()
                }
                true
            } == true
            binder?.let { try { it.unlinkToDeath(death, 0) } catch (_: Exception) { } }
            return retired
        }
    }
    companion object {
        private val gate = Mutex()
        @Volatile private var quarantined = false
    }
}
