package com.hasaan.busymode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hands-free assistant:
 *  1. Listens offline (Vosk) for "assistant" / "my assistant" — no audio leaves the phone here.
 *  2. Says "How can I help you?", records your request until you stop talking.
 *  3. Turns it into text with Groq Whisper and hands it to the engine; speaks the answer.
 *  4. If the answer is a question, listens again for your reply (no wake word needed).
 */
class VoiceAssistant(
    private val context: Context,
    private val settings: Settings,
    private val speaker: Speaker,
    private val onRequest: (session: Int, text: String) -> Unit
) {
    enum class State { OFF, LOADING, WAITING, PROMPTING, LISTENING, THINKING, SPEAKING, ERROR }

    private enum class Mode { WAKE, COMMAND, IDLE }

    companion object {
        private const val TAG = "VoiceAssistant"
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms
        private const val MODEL_ASSET = "vosk-model"
        private const val MODEL_VERSION = "small-en-us-0.15-v1"
        private val GRAMMAR = """["assistant", "my assistant", "hey assistant", "ok assistant", "[unk]"]"""

        @Volatile var state: State = State.OFF
            private set
        @Volatile var detail: String = ""
            private set
        private val stateListeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) = stateListeners.add(l)
        fun removeStateListener(l: () -> Unit) = stateListeners.remove(l)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile private var wakeEnabled = false
    @Volatile private var mode = Mode.IDLE
    @Volatile private var resetRecognizer = false
    @Volatile private var inSession = false
    private var thread: Thread? = null
    private var model: Model? = null
    private var session = 0
    private var followUp = false
    private var replyTimeout: Runnable? = null

    // command capture (audio thread only)
    private var capture = ShortArray(RATE * 16)
    private var captureLen = 0
    private var captureStart = 0L
    private var speechFrames = 0
    private var silenceFrames = 0
    private var speechStarted = false
    private var noiseFloor = 300.0

    private fun setState(s: State, d: String = "") {
        state = s
        detail = d
        main.post { stateListeners.forEach { it() } }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ public controls (main thread)

    /** Turn the always-on wake word on or off. */
    fun setWakeEnabled(enabled: Boolean) {
        wakeEnabled = enabled
        if (enabled) ensureThread()
        else if (!inSession) { stopThread(); setState(State.OFF) }
    }

    /** Start a conversation right now (button or notification), without the wake word. */
    fun talkNow() {
        if (inSession) return
        if (!hasMicPermission()) { setState(State.ERROR, "Microphone permission is needed"); return }
        ensureThread()
        beginSession()
    }

    /** The engine answered; speak it and listen again if it asked a question. */
    fun onReply(sessionId: Int, text: String, listen: Boolean) {
        if (sessionId != session || !inSession) return
        replyTimeout?.let { main.removeCallbacks(it) }
        setState(State.SPEAKING)
        speaker.say(text) { if (listen) startCapture(true) else endSession() }
    }

    fun shutdown() {
        wakeEnabled = false
        inSession = false
        stopThread()
        setState(State.OFF)
    }

    // ------------------------------------------------------------------ session flow (main thread)

    private fun beginSession() {
        inSession = true
        session++
        mode = Mode.IDLE
        setState(State.PROMPTING)
        speaker.say("How can I help you?") { startCapture(false) }
    }

    private fun startCapture(isFollowUp: Boolean) {
        if (!inSession) return
        followUp = isFollowUp
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {}
        main.postDelayed({
            if (!inSession) return@postDelayed
            captureLen = 0; speechFrames = 0; silenceFrames = 0; speechStarted = false
            captureStart = System.currentTimeMillis()
            mode = Mode.COMMAND
            setState(State.LISTENING)
        }, 250)
    }

    private fun endSession() {
        inSession = false
        replyTimeout?.let { main.removeCallbacks(it) }
        if (wakeEnabled) {
            resetRecognizer = true
            mode = Mode.WAKE
            setState(if (model != null) State.WAITING else State.ERROR, if (model != null) "" else detail)
        } else {
            stopThread()
            setState(State.OFF)
        }
    }

    private fun onCaptured(pcm: ShortArray, len: Int, heardSpeech: Boolean) {
        if (!heardSpeech) {
            speaker.say(if (followUp) "Okay, I'm here if you need me." else "Sorry, I didn't hear anything.") { endSession() }
            setState(State.SPEAKING)
            return
        }
        setState(State.THINKING)
        val key = settings.groqKey
        if (key.isBlank()) {
            speaker.say("Please add your Groq API key in the Busy Mode settings first.") { endSession() }
            return
        }
        val mySession = session
        worker.execute {
            val text = try {
                GroqStt.transcribe(key, GroqStt.wav(pcm, len), "Voice command to a WhatsApp assistant for ${settings.ownerName}.")
            } catch (e: Exception) {
                Log.w(TAG, "STT failed", e)
                main.post {
                    val msg = if ((e as? GroqStt.SttException)?.status == 401) "Your Groq API key was rejected." else "Sorry, I couldn't reach the speech service."
                    speaker.say(msg) { endSession() }
                }
                return@execute
            }
            main.post {
                if (mySession != session || !inSession) return@post
                if (isNoise(text)) {
                    speaker.say(if (followUp) "Okay, I'm here if you need me." else "Sorry, I didn't catch that.") { endSession() }
                    return@post
                }
                Log.i(TAG, "Heard: $text")
                val r = Runnable { if (inSession && mySession == session) speaker.say("Sorry, that is taking too long.") { endSession() } }
                replyTimeout = r
                main.postDelayed(r, 60_000)
                onRequest(mySession, text)
            }
        }
    }

    /** Whisper sometimes "hears" these in silence. */
    private fun isNoise(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', '!', '?', ' ')
        return t.isEmpty() || t in setOf("you", "thank you", "thanks for watching", "thank you for watching", "bye", ".", "uh", "um", "hmm")
    }

    // ------------------------------------------------------------------ audio thread

    @Volatile private var loopGen = 0

    private fun ensureThread() {
        if (running) return
        running = true
        val gen = ++loopGen
        thread = Thread({ audioLoop(gen) }, "voice-audio").apply { start() }
    }

    private fun stopThread() {
        running = false
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop(gen: Int) {
        var recognizer: Recognizer? = null
        var rec: AudioRecord? = null
        try {
            if (wakeEnabled) {
                setState(State.LOADING, "Preparing wake word…")
                recognizer = loadRecognizer()
            }
            if (!hasMicPermission()) { setState(State.ERROR, "Microphone permission is needed"); return }
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // A previous listening thread may still be releasing the microphone: retry briefly.
            for (attempt in 0 until 8) {
                rec?.release()
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
                if (rec.state == AudioRecord.STATE_INITIALIZED) break
                Thread.sleep(300)
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { setState(State.ERROR, "Microphone is busy"); return }
            rec.startRecording()
            if (!inSession) {
                mode = if (recognizer != null) Mode.WAKE else Mode.IDLE
                setState(if (recognizer != null) State.WAITING else if (wakeEnabled) State.ERROR else State.OFF, if (recognizer == null && wakeEnabled) detail else "")
            }
            val frame = ShortArray(FRAME)
            var lastWake = 0L
            while (running && gen == loopGen) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) continue
                if (speaker.isSpeaking) continue
                val rms = rms(frame, n)
                when (mode) {
                    Mode.WAKE -> {
                        val recog = recognizer ?: continue
                        if (resetRecognizer) { recog.reset(); resetRecognizer = false }
                        if (rms < noiseFloor * 3) noiseFloor = noiseFloor * 0.97 + rms * 0.03
                        val inCall = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                        if (inCall) continue
                        val final = recog.acceptWaveForm(frame, n)
                        val json = if (final) recog.result else recog.partialResult
                        val heard = JSONObject(json).let { it.optString("text").ifEmpty { it.optString("partial") } }
                        if (heard.contains("assistant") && System.currentTimeMillis() - lastWake > 3000) {
                            lastWake = System.currentTimeMillis()
                            recog.reset()
                            mode = Mode.IDLE
                            main.post { if (!inSession) beginSession() }
                        }
                    }
                    Mode.COMMAND -> captureFrame(frame, n, rms)
                    Mode.IDLE -> {}
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Voice loop failed", t)
            setState(State.ERROR, "Voice error: ${t.message}")
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            recognizer?.close()
        }
    }

    private fun captureFrame(frame: ShortArray, n: Int, rms: Double) {
        val threshold = max(500.0, noiseFloor * 2.5)
        if (captureLen + n <= capture.size) {
            System.arraycopy(frame, 0, capture, captureLen, n)
            captureLen += n
        }
        if (rms > threshold) {
            speechFrames++
            silenceFrames = 0
            if (speechFrames >= 4) speechStarted = true
        } else {
            if (!speechStarted) { speechFrames = 0; noiseFloor = noiseFloor * 0.95 + rms * 0.05 }
            else silenceFrames++
        }
        val elapsed = System.currentTimeMillis() - captureStart
        val done = (speechStarted && silenceFrames >= 50) || captureLen + n > capture.size || elapsed > 15_000
        val gaveUp = !speechStarted && elapsed > 7_000
        if (done || gaveUp) {
            mode = Mode.IDLE
            val copy = capture.copyOf(captureLen)
            val len = captureLen
            val heard = speechStarted
            main.post { onCaptured(copy, len, heard) }
        }
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    // ------------------------------------------------------------------ wake-word model

    private fun loadRecognizer(): Recognizer? {
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = model ?: Model(copyModel().absolutePath).also { model = it }
            Recognizer(m, RATE.toFloat(), GRAMMAR)
        } catch (t: Throwable) {
            Log.e(TAG, "Wake word model failed", t)
            detail = "Wake word unavailable (${t.message}). You can still tap Talk."
            null
        }
    }

    /** The model ships inside the app; copy it to storage once (Vosk needs real files). */
    private fun copyModel(): File {
        val dir = File(context.filesDir, "vosk-model")
        val stamp = File(dir, ".version")
        if (stamp.exists() && stamp.readText() == MODEL_VERSION) return dir
        dir.deleteRecursively()
        copyAssetDir(MODEL_ASSET, dir)
        stamp.writeText(MODEL_VERSION)
        return dir
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(target, child))
    }
}
