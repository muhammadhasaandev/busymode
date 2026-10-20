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
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hands-free assistant:
 *  1. Listens offline (Vosk) for "assistant" / "my assistant" — no audio leaves the phone here.
 *  2. Greets you ("Yes Hasaan, how can I help you?") and records until you have finished talking.
 *  3. Turns speech into text with Groq Whisper (Urdu or English) and hands it to the engine.
 *  4. Speaks the answer and shows options on screen; if it asked a question it listens again.
 */
class VoiceAssistant(
    private val context: Context,
    private val settings: Settings,
    private val speaker: Speaker,
    private val onRequest: (session: Int, text: String) -> Unit,
    private val onSessionStart: () -> Unit
) {
    enum class State { OFF, LOADING, WAITING, PROMPTING, LISTENING, THINKING, SPEAKING, ERROR }

    private enum class Mode { WAKE, COMMAND, IDLE }

    companion object {
        private const val TAG = "VoiceAssistant"
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms
        private const val MAX_RECORD_MS = 60_000
        private const val END_SILENCE_MS = 2_000 // you have finished when quiet this long
        private const val FIRST_WORD_WAIT_MS = 10_000
        private const val FOLLOW_UP_WAIT_MS = 15_000
        private const val MODEL_ASSET = "vosk-model"
        private const val MODEL_VERSION = "small-en-us-0.15-v1"
        private val GRAMMAR = """["assistant", "my assistant", "hey assistant", "ok assistant", "assistance", "assist", "[unk]"]"""

        @Volatile var state: State = State.OFF
            private set
        @Volatile var detail: String = ""
            private set
        /** The running assistant, for the assistant screen's buttons. */
        @Volatile var instance: VoiceAssistant? = null
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
    @Volatile private var finishRequested = false
    @Volatile private var loopGen = 0
    private var model: Model? = null
    private var session = 0
    private var sessionEndedAt = 0L
    private var followUp = false
    private var replyTimeout: Runnable? = null

    // command capture (audio thread only)
    private var capture = ShortArray(RATE * (MAX_RECORD_MS / 1000 + 1))
    private var captureLen = 0
    private var captureStart = 0L
    private var lastSpeechAt = 0L
    private var speechStarted = false
    private val recent = BooleanArray(10)
    private var recentIdx = 0
    private var floorDb = 40.0
    private var peakAboveFloor = 0.0
    private var frameCount = 0

    init { instance = this }

    private fun setState(s: State, d: String = "") {
        state = s
        detail = d
        main.post { stateListeners.forEach { it() } }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val isActive: Boolean get() = inSession

    // ------------------------------------------------------------------ public controls (main thread)

    /** Turn the always-on wake word on or off. */
    fun setWakeEnabled(enabled: Boolean) {
        wakeEnabled = enabled
        if (enabled) ensureThread()
        else if (!inSession) { stopThread(); setState(State.OFF) }
    }

    /** Start a new conversation now (button or notification), without the wake word. */
    fun talkNow() {
        if (inSession) return
        if (!hasMicPermission()) { setState(State.ERROR, "Microphone permission is needed"); return }
        ensureThread()
        beginSession()
    }

    /** Continue the current conversation: listen again without the greeting (mic button on screen). */
    fun listenAgain() {
        if (!hasMicPermission()) { setState(State.ERROR, "Microphone permission is needed"); return }
        speaker.stop()
        replyTimeout?.let { main.removeCallbacks(it) }
        if (!inSession) { inSession = true; if (System.currentTimeMillis() - sessionEndedAt > 4 * 60_000) session++ }
        ensureThread()
        startCapture(true)
    }

    /** "Done" button: stop recording now and process what was said. */
    fun finishNow() { if (mode == Mode.COMMAND) finishRequested = true }

    /** A tapped option or typed text, handled like something you said. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        speaker.stop()
        mode = Mode.IDLE
        if (!inSession) {
            inSession = true
            // Keep the same conversation (so "Option 2" still refers to the list on screen).
            if (System.currentTimeMillis() - sessionEndedAt > 4 * 60_000) session++
            ensureThread()
        }
        AssistantUi.setUser(text)
        sendRequest(text)
    }

    /** Close button: stop listening and talking. */
    fun cancel() {
        speaker.stop()
        endSession()
    }

    /** The engine answered: show it, speak it, and listen again if it asked something. */
    fun onReply(sessionId: Int, text: String, listen: Boolean, ui: JSONObject?, lang: String) {
        if (sessionId != session || !inSession) return
        replyTimeout?.let { main.removeCallbacks(it) }
        if (settings.assistantLanguage == "auto") settings.lastVoiceLang = if (lang == "en") "en" else "ur"
        AssistantUi.setReply(text, ui)
        setState(State.SPEAKING)
        speaker.say(text, lang) { if (listen) startCapture(true) else endSession() }
    }

    fun shutdown() {
        wakeEnabled = false
        inSession = false
        speaker.stop()
        stopThread()
        setState(State.OFF)
        if (instance === this) instance = null
    }

    // ------------------------------------------------------------------ session flow (main thread)

    private fun greeting(): Pair<String, String> {
        val lang = when (settings.assistantLanguage) { "ur" -> "ur"; "en" -> "en"; else -> settings.lastVoiceLang }
        if (lang != "ur") return settings.greeting to "en"
        val name = settings.firstName()
        return when {
            speaker.urduOk -> "جی $name، میں آپ کی کیا مدد کروں؟" to "ur"
            speaker.hindiOk -> "जी $name, मैं आपकी क्या मदद करूँ?" to "hi"
            else -> settings.greeting to "en"
        }
    }

    private fun beginSession() {
        inSession = true
        session++
        mode = Mode.IDLE
        AssistantUi.newConversation()
        onSessionStart()
        setState(State.PROMPTING)
        val (text, lang) = greeting()
        AssistantUi.setReply(text, null)
        speaker.say(text, lang) { startCapture(false) }
    }

    private fun startCapture(isFollowUp: Boolean) {
        if (!inSession) return
        followUp = isFollowUp
        mode = Mode.IDLE
        beep(ToneGenerator.TONE_PROP_BEEP, 120)
        main.postDelayed({
            if (!inSession) return@postDelayed
            captureLen = 0; speechStarted = false; finishRequested = false
            recent.fill(false); recentIdx = 0; peakAboveFloor = 0.0; frameCount = 0
            captureStart = System.currentTimeMillis(); lastSpeechAt = captureStart
            mode = Mode.COMMAND
            AssistantUi.setHint(if (followUp) "Listening… (tap Done when finished)" else "Listening… speak now")
            setState(State.LISTENING)
        }, 220)
    }

    private fun endSession() {
        inSession = false
        mode = Mode.IDLE
        sessionEndedAt = System.currentTimeMillis()
        replyTimeout?.let { main.removeCallbacks(it) }
        AssistantUi.setLevel(0f)
        if (wakeEnabled) {
            resetRecognizer = true
            mode = Mode.WAKE
            setState(if (model != null) State.WAITING else State.ERROR, if (model != null) "" else detail)
        } else {
            stopThread()
            setState(State.OFF)
        }
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            g.startTone(tone, ms)
            main.postDelayed({ g.release() }, ms + 200L)
        } catch (_: Exception) {}
    }

    /** Nothing was said: end quietly instead of nagging. */
    private fun endQuietly() {
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        AssistantUi.setHint("Tap the mic to talk again")
        endSession()
    }

    private fun onCaptured(pcm: ShortArray, len: Int, worthSending: Boolean) {
        if (!worthSending) { endQuietly(); return }
        setState(State.THINKING)
        AssistantUi.setHint("Understanding…")
        val key = settings.groqKey
        if (key.isBlank()) {
            AssistantUi.setReply("Please add your Groq API key in the Busy Mode settings first.", null)
            speaker.say("Please add your Groq API key in the Busy Mode settings first.") { endSession() }
            return
        }
        val mySession = session
        val language = when (settings.assistantLanguage) { "ur" -> "ur"; "en" -> "en"; else -> "" }
        val prompt = "${settings.firstName()} talking to his WhatsApp assistant. Sumaya ko message karo. Ali ko reply karo. Latest messages batao. سمیہ کو میسج کرو۔"
        worker.execute {
            val text = try {
                GroqStt.transcribe(key, GroqStt.wav(pcm, len), prompt, language)
            } catch (e: Exception) {
                Log.w(TAG, "STT failed", e)
                main.post {
                    if (mySession != session || !inSession) return@post
                    val msg = if ((e as? GroqStt.SttException)?.status == 401) "Your Groq API key was rejected." else "Sorry, I couldn't reach the speech service. Please check the internet."
                    AssistantUi.setReply(msg, null)
                    speaker.say(msg) { endSession() }
                }
                return@execute
            }
            main.post {
                if (mySession != session || !inSession) return@post
                if (isNoise(text)) { endQuietly(); return@post }
                Log.i(TAG, "Heard: $text")
                AssistantUi.setUser(text)
                sendRequest(text)
            }
        }
    }

    private fun sendRequest(text: String) {
        setState(State.THINKING)
        AssistantUi.setHint("Thinking…")
        val mySession = session
        replyTimeout?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (inSession && mySession == session) {
                AssistantUi.setReply("Sorry, that is taking too long. Please try again.", null)
                speaker.say("Sorry, that is taking too long.") { endSession() }
            }
        }
        replyTimeout = r
        main.postDelayed(r, 60_000)
        onRequest(mySession, text)
    }

    /** Whisper sometimes "hears" these in silence. */
    private fun isNoise(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', '!', '?', ' ', '۔')
        return t.length < 2 || t in setOf("you", "thank you", "thanks for watching", "thank you for watching", "bye", "uh", "um", "hmm", "شکریہ")
    }

    // ------------------------------------------------------------------ audio thread

    private fun ensureThread() {
        if (running) return
        running = true
        val gen = ++loopGen
        Thread({ audioLoop(gen) }, "voice-audio").start()
    }

    private fun stopThread() { running = false }

    @SuppressLint("MissingPermission")
    private fun audioLoop(gen: Int) {
        var recognizer: Recognizer? = null
        var rec: AudioRecord? = null
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null
        try {
            if (wakeEnabled) {
                if (model == null) setState(State.LOADING, "Preparing wake word…")
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
            // Level out quiet and loud voices so far-away speech is still picked up.
            try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
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
                when (mode) {
                    Mode.WAKE -> {
                        val recog = recognizer ?: continue
                        if (resetRecognizer) { recog.reset(); resetRecognizer = false }
                        val inCall = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                        if (inCall) continue
                        val final = recog.acceptWaveForm(frame, n)
                        val json = if (final) recog.result else recog.partialResult
                        val heard = JSONObject(json).let { it.optString("text").ifEmpty { it.optString("partial") } }
                        if (heard.contains("assist") && System.currentTimeMillis() - lastWake > 3000) {
                            lastWake = System.currentTimeMillis()
                            recog.reset()
                            mode = Mode.IDLE
                            main.post { if (!inSession) beginSession() }
                        }
                    }
                    Mode.COMMAND -> captureFrame(frame, n)
                    Mode.IDLE -> {}
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Voice loop failed", t)
            setState(State.ERROR, "Voice error: ${t.message}")
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            agc?.release(); ns?.release()
            rec?.release()
            recognizer?.close()
        }
    }

    /**
     * Records until you have clearly finished: speech is anything well above the room's background
     * level (learned while listening), and the recording ends after 2 seconds of quiet.
     */
    private fun captureFrame(frame: ShortArray, n: Int) {
        if (captureLen + n <= capture.size) {
            System.arraycopy(frame, 0, capture, captureLen, n)
            captureLen += n
        }
        val db = 20 * log10(rms(frame, n) + 1.0)
        frameCount++
        // Learn the room's background level from the quietest moments before you start talking.
        if (frameCount == 1) floorDb = db
        else if (!speechStarted) {
            if (db < floorDb) floorDb = db
            else if (db - floorDb < 4) floorDb = floorDb * 0.98 + db * 0.02
        }
        val above = db - floorDb
        val speechLike = above > 8 && db > 30
        recent[recentIdx] = speechLike
        recentIdx = (recentIdx + 1) % recent.size
        val now = System.currentTimeMillis()
        if (speechLike) lastSpeechAt = now
        if (!speechStarted && recent.count { it } >= 4) speechStarted = true
        peakAboveFloor = max(peakAboveFloor, above)
        if (frameCount % 3 == 0) AssistantUi.setLevel(((above - 3) / 30.0).coerceIn(0.0, 1.0).toFloat())

        val elapsed = now - captureStart
        val waitLimit = if (followUp) FOLLOW_UP_WAIT_MS else FIRST_WORD_WAIT_MS
        val finishedTalking = speechStarted && now - lastSpeechAt > END_SILENCE_MS
        val tooLong = elapsed > MAX_RECORD_MS || captureLen + n > capture.size
        val noOneSpoke = !speechStarted && elapsed > waitLimit
        if (finishedTalking || tooLong || noOneSpoke || finishRequested) {
            mode = Mode.IDLE
            finishRequested = false
            val copy = capture.copyOf(captureLen)
            val len = captureLen
            // Even if the level detector wasn't sure, send it when there was some sound: Whisper decides.
            val worth = speechStarted || peakAboveFloor > 6
            AssistantUi.setLevel(0f)
            main.post { onCaptured(copy, len, worth) }
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
