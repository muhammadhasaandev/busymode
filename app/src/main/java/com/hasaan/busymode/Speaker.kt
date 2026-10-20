package com.hasaan.busymode

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Text-to-speech with "finished speaking" callbacks, shared by alerts and the voice assistant. */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val active = AtomicInteger(0)
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private var ready = false
    private var tts: TextToSpeech? = null

    /** True while something is being spoken (the microphone ignores audio then). */
    val isSpeaking: Boolean get() = active.get() > 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            tts?.language = Locale.US
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            })
            main.post {
                ready = true
                pending.forEach { (t, cb) -> speakNow(t, cb) }
                pending.clear()
            }
        }
    }

    private fun finish(id: String?) {
        if (id == null) return
        val cb = callbacks.remove(id) ?: return
        active.decrementAndGet()
        // A short pause so the tail of our own voice isn't picked up by the microphone.
        main.postDelayed({ cb() }, 250)
    }

    /** Speak [text]; [onDone] runs on the main thread when it has finished. */
    fun say(text: String, onDone: (() -> Unit)? = null) {
        main.post { if (ready) speakNow(text, onDone) else pending.add(text to onDone) }
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val id = "u${System.nanoTime()}"
        active.incrementAndGet()
        callbacks[id] = { onDone?.invoke() }
        val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) ?: TextToSpeech.ERROR
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
