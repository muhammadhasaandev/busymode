package com.hasaan.busymode

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), NodeEngine.Listener {

    private lateinit var settings: Settings
    private var tts: TextToSpeech? = null
    private var waitingForCode = false
    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    // home
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var mainSwitch: MaterialSwitch
    private lateinit var linkCard: View
    private lateinit var phone: TextInputEditText
    private lateinit var getCode: MaterialButton
    private lateinit var codeBox: View
    private lateinit var codeView: TextView
    private lateinit var batteryCard: View
    private lateinit var unlink: MaterialButton

    // log
    private val adapter = LogAdapter { onLogTapped(it) }
    private lateinit var logEmpty: TextView
    private lateinit var logCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        val pages = mapOf(
            R.id.nav_home to findViewById<View>(R.id.page_home),
            R.id.nav_log to findViewById(R.id.page_log),
            R.id.nav_settings to findViewById(R.id.page_settings)
        )
        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            pages.forEach { (id, v) -> v.visibility = if (id == item.itemId) View.VISIBLE else View.GONE }
            if (item.itemId == R.id.nav_settings) loadSettings()
            true
        }

        setupHome()
        setupLog()
        setupSettings()
        askNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        NodeEngine.addListener(this)
        LogStore.addListener(logListener)
        refreshStatus()
        refreshLog()
        NodeEngine.pairingCode?.let { showCode(it) }
        NodeEngine.startupError?.let { onError(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
    }

    override fun onStop() {
        NodeEngine.removeListener(this)
        LogStore.removeListener(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ home

    private fun setupHome() {
        statusDot = findViewById(R.id.status_dot)
        statusTitle = findViewById(R.id.status_title)
        statusSub = findViewById(R.id.status_sub)
        mainSwitch = findViewById(R.id.main_switch)
        linkCard = findViewById(R.id.link_card)
        phone = findViewById(R.id.phone)
        getCode = findViewById(R.id.get_code)
        codeBox = findViewById(R.id.code_box)
        codeView = findViewById(R.id.code)
        batteryCard = findViewById(R.id.battery_card)
        unlink = findViewById(R.id.unlink)

        phone.setText(settings.lastPhone)
        mainSwitch.isChecked = settings.enabled
        mainSwitch.setOnCheckedChangeListener { _, on ->
            if (on == settings.enabled) return@setOnCheckedChangeListener
            if (on) {
                settings.enabled = true
                EngineService.start(this)
                if (settings.groqKey.isBlank()) {
                    toast("Tip: add your Groq API key in Settings for smart decisions")
                }
            } else {
                settings.enabled = false
                EngineService.stop(this)
            }
            refreshStatus()
        }

        getCode.setOnClickListener {
            val digits = phone.text.toString().filter { it.isDigit() }
            if (digits.length < 10) {
                phone.error = "Enter your full number with country code, e.g. 923001234567"
                return@setOnClickListener
            }
            settings.lastPhone = digits
            settings.enabled = true
            mainSwitch.isChecked = true
            EngineService.start(this)
            NodeEngine.pair(digits)
            waitingForCode = true
            getCode.isEnabled = false
            getCode.text = "Getting your code…"
            codeBox.visibility = View.GONE
            getCode.postDelayed({
                if (waitingForCode) {
                    waitingForCode = false
                    getCode.isEnabled = true
                    getCode.text = "Get code"
                    toast("No code yet. Check your internet connection and try again.")
                }
            }, 45_000)
        }

        findViewById<MaterialButton>(R.id.copy_code).setOnClickListener { copyCode() }
        findViewById<MaterialButton>(R.id.open_whatsapp).setOnClickListener { openWhatsApp() }
        findViewById<MaterialButton>(R.id.battery_btn).setOnClickListener { requestBatteryExemption() }
        findViewById<MaterialButton>(R.id.app_settings_btn).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        unlink.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unlink WhatsApp?")
                .setMessage("Busy Mode will stop reading your messages. You can link again any time with a new code.")
                .setPositiveButton("Unlink") { _, _ ->
                    EngineService.start(this)
                    NodeEngine.logout()
                    toast("Unlinking…")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun linkedOnDisk(): Boolean = try {
        val f = File(filesDir, "engine-data/wa-auth/creds.json")
        f.exists() && JSONObject(f.readText()).optBoolean("registered")
    } catch (e: Exception) { false }

    private fun refreshStatus() {
        val s = NodeEngine.status
        val engineKnows = s.state != "starting"
        val linked = if (engineKnows) s.registered else linkedOnDisk()

        val (color, title, sub) = when {
            !settings.enabled -> Triple(R.color.muted, "Busy Mode is off",
                if (linked) "Turn it on to start watching your messages" else "Link your WhatsApp below to get started")
            !engineKnows -> Triple(R.color.warn, "Starting…", "Getting ready")
            s.state == "connected" -> Triple(R.color.ok, "Busy Mode is on",
                "Watching messages" + (s.me?.let { " for +$it" } ?: ""))
            s.state == "connecting" -> Triple(R.color.warn, "Connecting…", "Reaching WhatsApp")
            s.state == "waiting_pair" -> Triple(R.color.warn, "Waiting for your code", "Enter the code in WhatsApp to finish linking")
            s.state == "logged_out" -> Triple(R.color.bad, "Not linked", "Link your WhatsApp below")
            else -> Triple(R.color.muted, "Paused", "")
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        statusTitle.text = title
        statusSub.text = sub
        statusSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        if (mainSwitch.isChecked != settings.enabled) mainSwitch.isChecked = settings.enabled

        linkCard.visibility = if (linked && s.state != "waiting_pair") View.GONE else View.VISIBLE
        unlink.visibility = if (linked) View.VISIBLE else View.GONE
    }

    private fun showCode(code: String) {
        waitingForCode = false
        getCode.isEnabled = true
        getCode.text = "Get a new code"
        codeView.text = EngineService.formatCode(code)
        codeBox.visibility = View.VISIBLE
        linkCard.visibility = View.VISIBLE
    }

    private fun copyCode() {
        val code = NodeEngine.pairingCode ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("WhatsApp pairing code", code))
        if (Build.VERSION.SDK_INT < 33) toast("Code copied")
    }

    private fun openWhatsApp() {
        val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (i != null) startActivity(i) else toast("WhatsApp is not installed")
    }

    // ------------------------------------------------------------------ battery & permissions

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun refreshBattery() {
        val ok = isIgnoringBattery()
        batteryCard.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok && !settings.batteryAsked) {
            settings.batteryAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow Busy Mode to run in the background")
                .setMessage("To keep watching your WhatsApp messages while the screen is off, Busy Mode needs to be excluded from battery optimization. On the next screen, tap Allow.")
                .setPositiveButton("Continue") { _, _ -> requestBatteryExemption() }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------------ engine events

    override fun onStatus(status: NodeEngine.Status) {
        refreshStatus()
        if (status.state == "connected") {
            codeBox.visibility = View.GONE
            getCode.text = "Get code"
        }
    }

    override fun onPairingCode(code: String) {
        showCode(code)
        copyCode()
    }

    override fun onError(message: String) {
        if (waitingForCode) {
            waitingForCode = false
            getCode.isEnabled = true
            getCode.text = "Get code"
        }
        MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ log

    private fun setupLog() {
        logEmpty = findViewById(R.id.log_empty)
        logCount = findViewById(R.id.log_count)
        findViewById<RecyclerView>(R.id.log_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<MaterialButton>(R.id.log_clear).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage("Clear the activity list?")
                .setPositiveButton("Clear") { _, _ -> LogStore.clear() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshLog() {
        val items = LogStore.all()
        adapter.submit(items)
        logEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        logCount.text = if (items.isEmpty()) "Recent messages" else "${items.size} recent messages"
    }

    private fun onLogTapped(e: LogStore.Entry) {
        val name = e.chatName
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setMessage(e.text + "\n\n" + e.reason)
            .setPositiveButton("Always important") { _, _ -> addToList(true, name) }
            .setNegativeButton("Always ignore") { _, _ -> addToList(false, name) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun addToList(important: Boolean, name: String) {
        settings.addToList(important, name)
        NodeEngine.sendConfig(settings)
        loadSettings()
        toast(if (important) "$name will always be announced" else "$name will always be ignored")
    }

    // ------------------------------------------------------------------ settings

    private fun setupSettings() {
        loadSettings()
        findViewById<MaterialButton>(R.id.get_key).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        findViewById<MaterialButton>(R.id.test_voice).setOnClickListener { testVoice() }
        findViewById<MaterialButton>(R.id.save).setOnClickListener { saveSettings() }
    }

    private fun field(id: Int) = findViewById<TextInputEditText>(id)

    private fun loadSettings() {
        field(R.id.groq_key).setText(settings.groqKey)
        field(R.id.owner_name).setText(settings.ownerName)
        field(R.id.reply_text).setText(settings.replyText)
        field(R.id.cooldown).setText(settings.replyCooldownMin.toString())
        field(R.id.important_list).setText(settings.importantList.joinToString("\n"))
        field(R.id.ignore_list).setText(settings.ignoreList.joinToString("\n"))
        field(R.id.model).setText(settings.model)
        findViewById<MaterialSwitch>(R.id.auto_reply).isChecked = settings.autoReply
        findViewById<MaterialSwitch>(R.id.voice_alerts).isChecked = settings.voiceAlerts
    }

    private fun saveSettings() {
        settings.groqKey = field(R.id.groq_key).text.toString()
        settings.ownerName = field(R.id.owner_name).text.toString().ifBlank { "Hasaan" }
        settings.replyText = field(R.id.reply_text).text.toString().ifBlank { Settings.DEFAULT_REPLY }
        settings.replyCooldownMin = field(R.id.cooldown).text.toString().toIntOrNull() ?: 60
        settings.importantList = Settings.lines(field(R.id.important_list).text.toString())
        settings.ignoreList = Settings.lines(field(R.id.ignore_list).text.toString())
        settings.model = field(R.id.model).text.toString()
        settings.autoReply = findViewById<MaterialSwitch>(R.id.auto_reply).isChecked
        settings.voiceAlerts = findViewById<MaterialSwitch>(R.id.voice_alerts).isChecked
        NodeEngine.sendConfig(settings)
        loadSettings()
        currentFocus?.clearFocus()
        toast("Settings saved")
    }

    private fun testVoice() {
        val name = field(R.id.owner_name).text.toString().split(',').first().trim().ifBlank { "Hasaan" }
        val line = "$name, you have an important message from Ali."
        val existing = tts
        if (existing != null) {
            existing.speak(line, TextToSpeech.QUEUE_FLUSH, null, "test")
            return
        }
        tts = TextToSpeech(applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "test")
            } else toast("Text-to-speech is not available on this phone")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Simple list of log entries. */
class LogAdapter(private val onTap: (LogStore.Entry) -> Unit) : RecyclerView.Adapter<LogAdapter.VH>() {
    private var items: List<LogStore.Entry> = emptyList()
    private val dayFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<LogStore.Entry>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val action: TextView = v.findViewById(R.id.action)
        val chat: TextView = v.findViewById(R.id.chat)
        val time: TextView = v.findViewById(R.id.time)
        val text: TextView = v.findViewById(R.id.text)
        val reason: TextView = v.findViewById(R.id.reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        val (label, color) = when (e.action) {
            "auto_replied" -> "Replied" to R.color.act_replied
            "announced" -> "Announced" to R.color.act_announced
            "noted" -> "Noted" to R.color.act_noted
            else -> "Ignored" to R.color.act_ignored
        }
        h.action.text = label
        h.action.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
        h.chat.text = e.chatName
        h.time.text = if (DateUtils.isToday(e.time)) timeFmt.format(Date(e.time)) else dayFmt.format(Date(e.time))
        h.text.text = if (e.isGroup) "${e.sender}: ${e.text}" else e.text
        h.reason.text = e.reason
        h.reason.visibility = if (e.reason.isBlank()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { onTap(e) }
    }
}
