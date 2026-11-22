package br.com.exames.featureresults

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechTest {
    @Test fun onlyInstalledLocalBrazilianVoiceIsEligible() {
        val br = Locale.forLanguageTag("pt-BR")
        assertTrue(eligibleVoice(br, false, false))
        assertFalse(eligibleVoice(br, true, false))
        assertFalse(eligibleVoice(br, false, true))
        assertFalse(eligibleVoice(Locale.forLanguageTag("pt-PT"), false, false))
    }

    @Test fun preservesEveryCharacterAndRejectsOversizeWordBeforePlayback() {
        val text = "Saída corrigida.\nModo sem IA. 🧪 Requer revisão."
        val chunks = speechSegments(text, 12)!!
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 12 })
        assertNull(speechSegments("Ok " + "X".repeat(13), 12))
        assertNull(speechSegments("", 12))
    }

    @Test fun lateCallbacksCannotAdvanceStoppedOrReplacementSession() {
        val seq = SpeechSequence()
        seq.begin(listOf("um", "dois"))
        val first = seq.currentId!!
        assertTrue(seq.advance(first))
        assertFalse(seq.advance(first))
        assertEquals("dois", seq.currentText)
        val second = seq.currentId!!
        seq.clear()
        assertNull(seq.currentText)
        assertFalse(seq.advance(second))
        seq.begin(listOf("novo"))
        assertFalse(seq.advance(second))
        assertEquals("novo", seq.currentText)
    }

    private class Host : SpeechHost {
        override val inputLimit = 16
        lateinit var ready: (Boolean) -> Unit
        lateinit var finished: (String, Boolean) -> Unit
        lateinit var lost: () -> Unit
        val utterances = mutableListOf<Pair<String, String>>()
        var stopped = 0
        var released = 0
        override fun initialize(ready: (Boolean) -> Unit, finished: (String, Boolean) -> Unit) {
            this.ready = ready
            this.finished = finished
        }
        override fun acquireFocus(lost: () -> Unit): Boolean { this.lost = lost; return true }
        override fun releaseFocus() { released++ }
        override fun speak(text: String, id: String): Boolean { utterances += id to text; return true }
        override fun stop() { stopped++ }
        override fun close() = Unit
    }

    @Test fun failedOrPendingInitializationNeverReceivesText() {
        val host = Host()
        val speech = OfflineSpeech(host)
        speech.initialize()
        speech.play("texto")
        host.ready(false)
        speech.play("texto")
        assertEquals(SpeechState.UNAVAILABLE, speech.state)
        assertTrue(host.utterances.isEmpty())
        speech.close()
        host.ready(true)
        speech.play("texto")
        assertTrue(host.utterances.isEmpty())
    }

    @Test fun focusLossStopAndDisposalDiscardPendingContentsAndLateCallbacks() {
        val host = Host()
        val speech = OfflineSpeech(host)
        speech.initialize()
        host.ready(true)
        val text = "Saída corrigida com revisão. Leitura parcial."
        speech.play(text)
        val stale = host.utterances.single().first
        host.lost()
        host.finished(stale, true)
        assertEquals(1, host.utterances.size)
        speech.play(text)
        host.finished(stale, false)
        while (speech.state == SpeechState.SPEAKING) host.finished(host.utterances.last().first, true)
        assertEquals(text, host.utterances.drop(1).joinToString("") { it.second })
        speech.play(text)
        val active = host.utterances.last().first
        speech.close()
        val count = host.utterances.size
        host.finished(active, true)
        assertEquals(count, host.utterances.size)
        assertEquals(host.stopped, host.released)
    }

    @Test fun unsegmentableSummaryFailsBeforeFirstSegment() {
        val host = Host()
        val speech = OfflineSpeech(host)
        speech.initialize()
        host.ready(true)
        speech.play("Início " + "x".repeat(17))
        assertTrue(host.utterances.isEmpty())
        assertEquals(SpeechState.ERROR, speech.state)
    }
}
