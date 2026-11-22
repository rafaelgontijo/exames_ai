package br.com.exames.featureresults

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

internal enum class SpeechState { CHECKING, UNAVAILABLE, READY, SPEAKING, ERROR }

internal fun eligibleVoice(locale: Locale, network: Boolean, missing: Boolean) =
    locale.language == "pt" && locale.country == "BR" && !network && !missing

private fun Voice.eligible() = eligibleVoice(locale, isNetworkConnectionRequired,
    features == null || TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features)

/** Preflight the entire text. Never split a Unicode scalar or a spoken word. */
internal fun speechSegments(text: String, limit: Int): List<String>? {
    if (limit <= 0 || text.isBlank()) return null
    val chunks = mutableListOf<String>()
    var start = 0
    while (text.length - start > limit) {
        var end = start + limit
        while (end > start && !text[end - 1].isWhitespace()) end--
        if (end == start) return null
        chunks += text.substring(start, end)
        start = end
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}

/** Each callback addresses one utterance, including after stop, replacement or duplicate completion. */
internal class SpeechSequence {
    private var generation = 0L
    private var chunks = emptyList<String>()
    private var index = 0
    val currentId get() = if (index in chunks.indices) "$generation-$index" else null
    val currentText get() = chunks.getOrNull(index)
    fun begin(prepared: List<String>) { clear(); chunks = prepared }
    fun advance(id: String): Boolean {
        if (id != currentId) return false
        index++
        return true
    }
    fun clear() { generation++; chunks = emptyList(); index = 0 }
}

internal interface SpeechHost {
    val inputLimit: Int
    fun initialize(ready: (Boolean) -> Unit, finished: (String, Boolean) -> Unit)
    fun acquireFocus(lost: () -> Unit): Boolean
    fun releaseFocus()
    fun speak(text: String, id: String): Boolean
    fun stop()
    fun close()
}

/** Main-thread owner. No queue or text is retained after the session ends. */
internal class OfflineSpeech(private val host: SpeechHost) {
    constructor(context: Context) : this(AndroidSpeechHost(context))

    var state by mutableStateOf(SpeechState.CHECKING)
        private set
    private var closed = false
    private val sequence = SpeechSequence()

    fun initialize() = host.initialize({ ready ->
        if (!closed) state = if (ready) SpeechState.READY else SpeechState.UNAVAILABLE
    }, { id, success ->
        if (!closed && id == sequence.currentId) {
            if (!success) fail()
            else if (sequence.advance(id)) {
                if (sequence.currentId == null) stop() else speakCurrent()
            }
        }
    })

    fun play(text: String) {
        if (closed || state !in setOf(SpeechState.READY, SpeechState.ERROR)) return
        val chunks = speechSegments(text, host.inputLimit) ?: return fail()
        if (!host.acquireFocus(::stop)) return fail()
        sequence.begin(chunks)
        state = SpeechState.SPEAKING
        speakCurrent()
    }

    private fun speakCurrent() {
        if (!host.speak(sequence.currentText ?: return, sequence.currentId ?: return)) fail()
    }
    private fun fail() { stop(); state = SpeechState.ERROR }

    fun stop() {
        sequence.clear()
        if (state == SpeechState.SPEAKING) {
            state = SpeechState.READY
            host.stop()
            host.releaseFocus()
        }
    }

    fun close() {
        closed = true
        stop()
        host.close()
    }
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class AndroidSpeechHost(private val context: Context) : SpeechHost {
    override val inputLimit get() = TextToSpeech.getMaxSpeechInputLength()
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioManager.OnAudioFocusChangeListener? = null
    private var engine: TextToSpeech? = null
    private var voiceName: String? = null
    private var closed = false
    private var attempt = 0

    override fun initialize(ready: (Boolean) -> Unit, finished: (String, Boolean) -> Unit) {
        val action = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val packages = try { context.packageManager.queryIntentServices(action, 0).map { it.serviceInfo.packageName }.distinct() }
        catch (_: SecurityException) { ready(false); return }
        fun tryEngine(candidates: Iterator<String>) {
            engine?.shutdown()
            engine = null
            if (closed || !candidates.hasNext()) { ready(false); return }
            val generation = ++attempt
            engine = TextToSpeech(context, { status -> main.post {
                if (!closed && generation == attempt) {
                    val tts = engine
                    val configured = status == TextToSpeech.SUCCESS && tts != null && try { configure(tts, finished) }
                        catch (_: RuntimeException) { false } // Engine can disappear while its voice list is being read.
                    if (configured) ready(true)
                    else tryEngine(candidates)
                }
            } }, candidates.next())
        }
        tryEngine(packages.iterator())
    }

    private fun configure(tts: TextToSpeech, finished: (String, Boolean) -> Unit): Boolean {
        val voice = tts.voices.orEmpty().filter { it.eligible() }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS && tts.voice?.name == it.name } ?: return false
        voiceName = voice.name
        return tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { main.post { finished(id, true) } }
            override fun onError(id: String) { main.post { finished(id, false) } }
            override fun onStop(id: String, interrupted: Boolean) { main.post { finished(id, false) } }
        }) == TextToSpeech.SUCCESS
    }

    override fun acquireFocus(lost: () -> Unit): Boolean {
        val listener = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(change: Int) { if (change < 0 && focus === this) lost() }
        }
        if (audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = listener
        return true
    }

    override fun releaseFocus() {
        val previous = focus
        focus = null
        previous?.let { audio.abandonAudioFocus(it) }
    }

    override fun speak(text: String, id: String): Boolean {
        val tts = engine ?: return false
        val voice = tts.voice
        if (voice == null || voice.name != voiceName || !voice.eligible()) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
    }

    override fun stop() { engine?.stop() }
    override fun close() {
        closed = true
        engine?.shutdown()
        engine = null
    }
}
