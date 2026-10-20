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

/**
 * Text-to-speech with "finished speaking" callbacks, shared by alerts and the voice assistant.
 * Speaks English, and Urdu when the phone has an Urdu voice (otherwise a Hindi voice, which
 * sounds the same for everyday speech).
 */
class Speaker(context: Context, private val onReady: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val active = AtomicInteger(0)
    private val pending = ArrayList<Triple<String, String, (() -> Unit)?>>()
    private var ready = false
    private var tts: TextToSpeech? = null

    /** The phone has a usable Urdu / Hindi voice. */
    @Volatile var urduOk = false
        private set
    @Volatile var hindiOk = false
        private set

    /** True while something is being spoken (the microphone ignores audio then). */
    val isSpeaking: Boolean get() = active.get() > 0

    private val urdu = Locale("ur", "PK")
    private val hindi = Locale("hi", "IN")

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            urduOk = available(t, urdu) || available(t, Locale("ur"))
            hindiOk = available(t, hindi)
            t.language = Locale.US
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            })
            main.post {
                ready = true
                pending.forEach { (text, lang, cb) -> speakNow(text, lang, cb) }
                pending.clear()
                onReady()
            }
        }
    }

    private fun available(t: TextToSpeech, l: Locale) = try {
        t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE
    } catch (_: Exception) { false }

    private fun finish(id: String?) {
        if (id == null) return
        val cb = callbacks.remove(id) ?: return
        active.decrementAndGet()
        // A short pause so the tail of our own voice isn't picked up by the microphone.
        main.postDelayed({ cb() }, 250)
    }

    /** Speak [text] in [lang] ("en", "ur" or "hi"); [onDone] runs on the main thread afterwards. */
    fun say(text: String, lang: String = "en", onDone: (() -> Unit)? = null) {
        main.post { if (ready) speakNow(text, lang, onDone) else pending.add(Triple(text, lang, onDone)) }
    }

    private fun speakNow(text: String, lang: String, onDone: (() -> Unit)?) {
        val t = tts
        val id = "u${System.nanoTime()}"
        active.incrementAndGet()
        callbacks[id] = { onDone?.invoke() }
        if (t == null) { finish(id); return }
        t.language = when (lang) {
            "ur" -> if (urduOk) urdu else if (hindiOk) hindi else Locale.US
            "hi" -> if (hindiOk) hindi else if (urduOk) urdu else Locale.US
            else -> Locale.US
        }
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    /** Stop talking now (e.g. the user tapped an option or closed the assistant). */
    fun stop() {
        tts?.stop()
        val ids = callbacks.keys.toList()
        ids.forEach { id -> callbacks.remove(id)?.let { active.decrementAndGet() } }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
