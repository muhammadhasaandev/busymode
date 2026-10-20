package com.hasaan.busymode

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Keeps the WhatsApp engine alive in the background, speaks alerts, shows reply approvals and
 * runs the voice assistant.
 */
class EngineService : Service(), NodeEngine.Listener {

    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceAssistant
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertId = 100
    private var micForeground = false
    private val io = Executors.newSingleThreadExecutor()
    private var contactsSentAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        ApprovalStore.init(this)
        goForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BusyMode::engine").apply {
                setReferenceCounted(false)
                acquire()
            }
        speaker = Speaker(this) {
            // Tell the assistant which voices exist, so it writes Urdu in a script the phone can speak.
            Settings.ttsUrdu = speaker.urduOk
            Settings.ttsHindi = speaker.hindiOk
            NodeEngine.sendConfig(settings)
        }
        voice = VoiceAssistant(this, settings, speaker,
            onRequest = { session, text ->
                sendContacts(force = false)
                NodeEngine.voice(session, text)
            },
            onSessionStart = { showAssistantScreen() }
        )
        NodeEngine.addListener(this)
        NodeEngine.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.enabled = false
                NodeEngine.stop()
                voice.shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE -> {
                handleApprovalAction(intent.getStringExtra(EXTRA_ID) ?: "", intent.getIntExtra(EXTRA_INDEX, -1))
            }
            ACTION_TALK -> {
                goForeground()
                if (micForeground || Build.VERSION.SDK_INT < 30) voice.talkNow()
                else notifyAlert("Voice assistant", "Open Busy Mode once to allow the microphone, then try again.", NOTIF_VOICE, openApp())
            }
            else -> {
                goForeground()
                NodeEngine.sendConfig(settings)
                NodeEngine.start()
                updateVoice()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        NodeEngine.removeListener(this)
        voice.shutdown()
        speaker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------ voice

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateVoice() {
        val want = settings.voiceWake && micGranted()
        if (want && !micForeground && Build.VERSION.SDK_INT >= 30) {
            // Android only lets the app use the microphone in the background if it was allowed
            // while the app was open. Ask the user to open the app once (e.g. after a restart).
            notifyAlert("Voice assistant is paused", "Tap to turn the \"Assistant\" wake word back on.", NOTIF_VOICE, openApp())
            voice.setWakeEnabled(false)
            return
        }
        NotificationManagerCompat.from(this).cancel(NOTIF_VOICE)
        voice.setWakeEnabled(want)
    }

    private fun sendContacts(force: Boolean) {
        if (!ContactsReader.hasPermission(this)) return
        if (!force && System.currentTimeMillis() - contactsSentAt < 10 * 60 * 1000) return
        contactsSentAt = System.currentTimeMillis()
        io.execute { NodeEngine.contacts(ContactsReader.read(this)) }
    }

    // ------------------------------------------------------------ engine events

    override fun onConnected() {
        NodeEngine.sendConfig(settings)
        sendContacts(force = true)
        NodeEngine.start()
        // Re-offer anything still waiting for approval.
        ApprovalStore.all().forEach { showApproval(it) }
    }

    override fun onStatus(status: NodeEngine.Status) {
        NotificationManagerCompat.from(this).let {
            if (it.areNotificationsEnabled()) try { it.notify(NOTIF_SERVICE, buildServiceNotification(statusText(status))) } catch (_: SecurityException) {}
        }
    }

    override fun onPairingCode(code: String) {
        notifyAlert("Your WhatsApp pairing code", "${formatCode(code)}  —  enter it in WhatsApp › Linked devices › Link with phone number instead", NOTIF_PAIR, null)
    }

    override fun onAnnounce(text: String) {
        speaker.say(text)
        notifyAlert(getString(R.string.app_name), text, alertId++, null)
    }

    override fun onEvent(entry: JSONObject) {
        if (entry.optBoolean("quiet")) return
        LogStore.add(LogStore.Entry.from(entry))
    }

    override fun onApproval(item: JSONObject) {
        val a = ApprovalStore.Item.from(item)
        ApprovalStore.put(a)
        showApproval(a)
    }

    override fun onApprovalDone(id: String, status: String) {
        ApprovalStore.remove(id)?.let { NotificationManagerCompat.from(this).cancel(it.notificationId) }
    }

    override fun onSay(session: Int, text: String, listen: Boolean, ui: JSONObject?, lang: String) {
        voice.onReply(session, text, listen, ui, lang)
    }

    /** Bring up the assistant screen (needs "Display over other apps" when the app is in the background). */
    private fun showAssistantScreen() {
        val i = Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Log.i(TAG, "Assistant screen not allowed: ${e.message}") }
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(this)) {
            val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notifyAlert("Assistant is listening", "Tap to see the assistant screen", NOTIF_VOICE, pi)
        }
    }

    override fun onError(message: String) {
        if (message.contains("reply", ignoreCase = true)) notifyAlert("Reply not sent", message, alertId++, null)
    }

    // ------------------------------------------------------------ approvals

    private fun handleApprovalAction(id: String, index: Int) {
        val item = ApprovalStore.get(id)
        if (item == null) {
            NotificationManagerCompat.from(this).cancel(10_000 + (id.hashCode() and 0x7ffff))
            return
        }
        if (index < 0) {
            Approvals.reject(this, item)
        } else {
            val text = item.suggestions.getOrNull(index) ?: return
            Approvals.approve(this, item, text)
            Toast.makeText(this, "Sending to ${item.chatName}: $text", Toast.LENGTH_SHORT).show()
        }
        NotificationManagerCompat.from(this).cancel(item.notificationId)
    }

    private fun showApproval(a: ApprovalStore.Item) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val big = StringBuilder(a.text)
        a.suggestions.forEachIndexed { i, s -> big.append("\n\n").append(i + 1).append(". ").append(s) }
        val title = if (a.isGroup) "${a.sender} in ${a.chatName}" else a.chatName
        val b = NotificationCompat.Builder(this, App.CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reply to $title?")
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big.toString()))
            .setContentIntent(openApp(MainActivity.TAB_REPLIES))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
        a.suggestions.take(2).forEachIndexed { i, _ -> b.addAction(0, "Send ${i + 1}", approvalIntent(a, i)) }
        b.addAction(0, "Don't send", approvalIntent(a, -1))
        try { nm.notify(a.notificationId, b.build()) } catch (_: SecurityException) {}
    }

    private fun approvalIntent(a: ApprovalStore.Item, index: Int): PendingIntent {
        val i = Intent(this, EngineService::class.java).setAction(ACTION_APPROVE)
            .putExtra(EXTRA_ID, a.id).putExtra(EXTRA_INDEX, index)
            .setData(android.net.Uri.parse("busymode://approve/${a.id}/$index"))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, a.notificationId + index + 1, i, flags)
        else PendingIntent.getService(this, a.notificationId + index + 1, i, flags)
    }

    // ------------------------------------------------------------ notifications

    private fun statusText(s: NodeEngine.Status): String = when (s.state) {
        "connected" -> "Watching your messages" + (s.me?.let { " (+$it)" } ?: "")
        "connecting" -> "Connecting to WhatsApp…"
        "waiting_pair" -> "Waiting for you to enter the pairing code"
        "logged_out" -> "Not linked — open the app to link WhatsApp"
        "disconnected" -> "Paused"
        else -> "Starting…"
    }

    /** Start (or refresh) the foreground service, including the microphone when allowed. */
    private fun goForeground() {
        val n = buildServiceNotification(statusText(NodeEngine.status))
        val wantMic = micGranted() // the wake word and the Talk button both need it
        if (Build.VERSION.SDK_INT >= 34) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            micForeground = false
            if (wantMic && micGranted()) {
                try {
                    startForeground(NOTIF_SERVICE, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    micForeground = true
                    return
                } catch (e: Exception) {
                    Log.i(TAG, "Microphone not allowed from background right now: ${e.message}")
                }
            }
            startForeground(NOTIF_SERVICE, n, base)
        } else if (Build.VERSION.SDK_INT >= 30 && micGranted()) {
            try {
                startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micForeground = true
            } catch (e: Exception) {
                startForeground(NOTIF_SERVICE, n)
                micForeground = false
            }
        } else {
            startForeground(NOTIF_SERVICE, n)
            micForeground = Build.VERSION.SDK_INT < 30
        }
    }

    private fun openApp(tab: String? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (tab != null) i.putExtra(MainActivity.EXTRA_TAB, tab)
        return PendingIntent.getActivity(this, (tab ?: "home").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildServiceNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val talk = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TALK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, "Talk", talk)
            .addAction(0, getString(R.string.turn_off), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyAlert(title: String, text: String, id: Int, intent: PendingIntent?) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val pi = intent ?: run {
            val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                ?: Intent(this, MainActivity::class.java)
            PendingIntent.getActivity(this, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
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
        private const val TAG = "EngineService"
        const val ACTION_STOP = "com.hasaan.busymode.STOP"
        const val ACTION_APPROVE = "com.hasaan.busymode.APPROVE"
        const val ACTION_TALK = "com.hasaan.busymode.TALK"
        const val EXTRA_ID = "id"
        const val EXTRA_INDEX = "index"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_PAIR = 2
        private const val NOTIF_VOICE = 3

        fun formatCode(code: String) = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, EngineService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Approve or reject a reply suggestion, from the notification or the app. */
object Approvals {
    fun approve(context: Context, item: ApprovalStore.Item, text: String) {
        NodeEngine.approve(item.id, item.chatJid, text)
        ApprovalStore.remove(item.id)
        NotificationManagerCompat.from(context).cancel(item.notificationId)
    }

    fun reject(context: Context, item: ApprovalStore.Item) {
        NodeEngine.reject(item.id)
        ApprovalStore.remove(item.id)
        NotificationManagerCompat.from(context).cancel(item.notificationId)
    }
}
