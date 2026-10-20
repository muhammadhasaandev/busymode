package com.hasaan.busymode

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/** Keeps the WhatsApp engine alive in the background and speaks the alerts it raises. */
class EngineService : Service(), NodeEngine.Listener {

    private lateinit var settings: Settings
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayList<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        goForeground(statusText(NodeEngine.status))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BusyMode::engine").apply {
                setReferenceCounted(false)
                acquire()
            }
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
                pendingSpeech.forEach { speakNow(it) }
                pendingSpeech.clear()
            }
        }
        NodeEngine.addListener(this)
        NodeEngine.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.enabled = false
                NodeEngine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                NodeEngine.sendConfig(settings)
                NodeEngine.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        NodeEngine.removeListener(this)
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------ engine events

    override fun onConnected() {
        NodeEngine.sendConfig(settings)
        NodeEngine.start()
    }

    override fun onStatus(status: NodeEngine.Status) {
        NotificationManagerCompat.from(this).let {
            if (it.areNotificationsEnabled()) it.notify(NOTIF_SERVICE, buildServiceNotification(statusText(status)))
        }
    }

    override fun onPairingCode(code: String) {
        notifyAlert("Your WhatsApp pairing code", "${formatCode(code)}  —  enter it in WhatsApp › Linked devices › Link with phone number instead", NOTIF_PAIR)
    }

    override fun onAnnounce(text: String) {
        speak(text)
        notifyAlert(getString(R.string.app_name), text, alertId++)
    }

    override fun onEvent(entry: JSONObject) {
        if (entry.optBoolean("quiet")) return
        LogStore.add(LogStore.Entry.from(entry))
    }

    // ------------------------------------------------------------ helpers

    private fun speak(text: String) {
        if (ttsReady) speakNow(text) else pendingSpeech.add(text)
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "a${System.nanoTime()}")
    }

    private fun statusText(s: NodeEngine.Status): String = when (s.state) {
        "connected" -> "Watching your messages" + (s.me?.let { " (+$it)" } ?: "")
        "connecting" -> "Connecting to WhatsApp…"
        "waiting_pair" -> "Waiting for you to enter the pairing code"
        "logged_out" -> "Not linked — open the app to link WhatsApp"
        "disconnected" -> "Paused"
        else -> "Starting…"
    }

    private fun goForeground(text: String) {
        val n = buildServiceNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_SERVICE, n)
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.turn_off), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyAlert(title: String, text: String, id: Int) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, App.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    companion object {
        const val ACTION_STOP = "com.hasaan.busymode.STOP"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_PAIR = 2

        fun formatCode(code: String) = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }
    }
}
