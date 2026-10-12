package com.opticalcenter.spatialnav.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Spoken replies via [TextToSpeech]. Text requested before the engine is ready is spoken once it is. */
class SpeechOutput(context: Context) {
    private var ready = false
    private val pending = mutableListOf<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pending.forEach { speak(it, interrupt = false) }
            pending.clear()
        }
    }

    /**
     * Speak [text]. With [interrupt] it replaces whatever is playing (assistant replies);
     * without, it queues behind it (navigation prompts that must not cut each other off).
     */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!ready) {
            if (interrupt) pending.clear()
            pending += text
            return
        }
        tts.language = Locale.getDefault()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "utt-${System.nanoTime()}")
    }

    fun stop() {
        pending.clear()
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
