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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
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
    private val approvalsListener: () -> Unit = { runOnUiThread { refreshReplies() } }
    private val voiceListener: () -> Unit = { runOnUiThread { refreshVoice() } }
    private lateinit var bottomNav: BottomNavigationView
    private var afterPermissions: ((Map<String, Boolean>) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        afterPermissions?.invoke(result)
        afterPermissions = null
    }

    // replies + voice
    private val approvalAdapter = ApprovalAdapter({ item, text -> confirmSend(item, text) }, { item -> skipApproval(item) })
    private lateinit var repliesEmpty: TextView
    private lateinit var voiceSwitch: MaterialSwitch
    private lateinit var voiceStatus: TextView

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
            R.id.nav_replies to findViewById(R.id.page_replies),
            R.id.nav_log to findViewById(R.id.page_log),
            R.id.nav_settings to findViewById(R.id.page_settings)
        )
        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            pages.forEach { (id, v) -> v.visibility = if (id == item.itemId) View.VISIBLE else View.GONE }
            if (item.itemId == R.id.nav_settings) loadSettings()
            true
        }

        setupHome()
        setupVoice()
        setupReplies()
        setupLog()
        setupSettings()
        askNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getStringExtra(EXTRA_TAB) == TAB_REPLIES) bottomNav.selectedItemId = R.id.nav_replies
        if (i.getBooleanExtra(EXTRA_TALK, false)) {
            i.removeExtra(EXTRA_TALK)
            talkNow()
        }
    }

    override fun onStart() {
        super.onStart()
        NodeEngine.addListener(this)
        LogStore.addListener(logListener)
        ApprovalStore.addListener(approvalsListener)
        VoiceAssistant.addStateListener(voiceListener)
        // Opening the app lets Android grant the microphone to the background service again.
        if (settings.enabled) EngineService.start(this)
        refreshStatus()
        refreshLog()
        refreshReplies()
        refreshVoice()
        NodeEngine.pairingCode?.let { showCode(it) }
        NodeEngine.startupError?.let { onError(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
        refreshVoice()
    }

    override fun onStop() {
        NodeEngine.removeListener(this)
        LogStore.removeListener(logListener)
        ApprovalStore.removeListener(approvalsListener)
        VoiceAssistant.removeStateListener(voiceListener)
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

    // ------------------------------------------------------------------ permissions

    private fun askPermissions(perms: Array<String>, then: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { then(perms.associateWith { true }); return }
        afterPermissions = then
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshContactsButton() {
        val b = findViewById<MaterialButton>(R.id.contacts_btn)
        val ok = ContactsReader.hasPermission(this)
        b.text = if (ok) "Phone contacts: allowed" else "Allow access to phone contacts"
        b.isEnabled = !ok
    }

    // ------------------------------------------------------------------ voice

    private fun setupVoice() {
        voiceSwitch = findViewById(R.id.voice_switch)
        voiceStatus = findViewById(R.id.voice_status)
        voiceSwitch.isChecked = settings.voiceWake
        voiceSwitch.setOnCheckedChangeListener { _, on ->
            if (on == settings.voiceWake) return@setOnCheckedChangeListener
            if (!on) {
                settings.voiceWake = false
                if (settings.enabled) EngineService.start(this)
                refreshVoice()
                return@setOnCheckedChangeListener
            }
            askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)) { r ->
                if (r[Manifest.permission.RECORD_AUDIO] == false) {
                    voiceSwitch.isChecked = false
                    toast("The microphone is needed to hear “Assistant”")
                    return@askPermissions
                }
                settings.voiceWake = true
                if (!settings.enabled) {
                    toast("Turn on Busy Mode at the top to use the voice assistant")
                } else EngineService.start(this)
                refreshVoice()
            }
        }
        findViewById<MaterialButton>(R.id.talk_now).setOnClickListener { talkNow() }
        findViewById<MaterialButton>(R.id.overlay_btn).setOnClickListener {
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun talkNow() {
        if (!settings.enabled) { toast("Turn on Busy Mode at the top first"); return }
        askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)) { r ->
            if (r[Manifest.permission.RECORD_AUDIO] == false) { toast("The microphone is needed to talk to the assistant"); return@askPermissions }
            EngineService.start(this, EngineService.ACTION_TALK)
        }
    }

    private fun refreshVoice() {
        if (voiceSwitch.isChecked != settings.voiceWake) voiceSwitch.isChecked = settings.voiceWake
        findViewById<MaterialButton>(R.id.overlay_btn).visibility =
            if (settings.voiceWake && Build.VERSION.SDK_INT >= 23 && !AndroidSettings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        voiceStatus.text = when (VoiceAssistant.state) {
            VoiceAssistant.State.LOADING -> "Getting ready…"
            VoiceAssistant.State.WAITING -> "Listening for “Assistant”"
            VoiceAssistant.State.PROMPTING, VoiceAssistant.State.SPEAKING -> "Speaking…"
            VoiceAssistant.State.LISTENING -> "Listening… speak now"
            VoiceAssistant.State.THINKING -> "Thinking…"
            VoiceAssistant.State.ERROR -> VoiceAssistant.detail.ifBlank { "Voice assistant problem" }
            VoiceAssistant.State.OFF -> if (settings.voiceWake) "Starting…" else "Wake word off — tap Talk now, or turn it on"
        }
    }

    // ------------------------------------------------------------------ replies

    private fun setupReplies() {
        repliesEmpty = findViewById(R.id.replies_empty)
        findViewById<RecyclerView>(R.id.replies_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = approvalAdapter
        }
    }

    private fun refreshReplies() {
        val items = ApprovalStore.all()
        approvalAdapter.submit(items)
        repliesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val badge = bottomNav.getOrCreateBadge(R.id.nav_replies)
        badge.isVisible = items.isNotEmpty()
        badge.number = items.size
    }

    private fun confirmSend(item: ApprovalStore.Item, suggestion: String) {
        val input = EditText(this).apply {
            setText(suggestion)
            setSelection(suggestion.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send to ${item.chatName}?")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                if (!settings.enabled) EngineService.start(this)
                Approvals.approve(this, item, text)
                toast("Sending to ${item.chatName}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun skipApproval(item: ApprovalStore.Item) {
        Approvals.reject(this, item)
        toast("Not sent")
    }

    // ------------------------------------------------------------------ settings

    private fun setupSettings() {
        loadSettings()
        findViewById<MaterialButton>(R.id.get_key).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        findViewById<MaterialButton>(R.id.test_voice).setOnClickListener { testVoice() }
        findViewById<MaterialButton>(R.id.tts_settings).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (e: Exception) { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
        }
        findViewById<MaterialButton>(R.id.save).setOnClickListener { saveSettings() }
        findViewById<MaterialButton>(R.id.contacts_btn).setOnClickListener {
            askPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) {
                refreshContactsButton()
                if (settings.enabled) EngineService.start(this)
            }
        }
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
        findViewById<RadioGroup>(R.id.reply_mode).check(
            when (settings.replyMode) { "auto" -> R.id.mode_auto; "off" -> R.id.mode_off; else -> R.id.mode_suggest }
        )
        field(R.id.country_code).setText(settings.countryCode)
        field(R.id.agent_model).setText(settings.agentModel)
        refreshContactsButton()
        findViewById<RadioGroup>(R.id.assistant_lang).check(
            when (settings.assistantLanguage) { "ur" -> R.id.lang_ur; "en" -> R.id.lang_en; else -> R.id.lang_auto }
        )
        field(R.id.greeting).setText(settings.greeting)
        findViewById<TextView>(R.id.urdu_voice_status).text = when {
            Settings.ttsUrdu -> "Urdu voice: installed ✓"
            Settings.ttsHindi -> "Urdu voice not installed — using the Hindi voice for Urdu (sounds the same). For a true Urdu voice, add Urdu in Phone voice settings."
            else -> "No Urdu/Hindi voice found — Urdu answers will sound English-accented. Add Urdu in Phone voice settings."
        }
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
        settings.replyMode = when (findViewById<RadioGroup>(R.id.reply_mode).checkedRadioButtonId) {
            R.id.mode_auto -> "auto"; R.id.mode_off -> "off"; else -> "suggest"
        }
        settings.countryCode = field(R.id.country_code).text.toString()
        settings.agentModel = field(R.id.agent_model).text.toString()
        settings.assistantLanguage = when (findViewById<RadioGroup>(R.id.assistant_lang).checkedRadioButtonId) {
            R.id.lang_ur -> "ur"; R.id.lang_en -> "en"; else -> "auto"
        }
        settings.greeting = field(R.id.greeting).text.toString()
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

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_TALK = "talk"
        const val TAB_REPLIES = "replies"
    }
}

/** Cards for messages waiting for reply approval. */
class ApprovalAdapter(
    private val onPick: (ApprovalStore.Item, String) -> Unit,
    private val onSkip: (ApprovalStore.Item) -> Unit
) : RecyclerView.Adapter<ApprovalAdapter.VH>() {
    private var items: List<ApprovalStore.Item> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ApprovalStore.Item>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val chat: TextView = v.findViewById(R.id.ap_chat)
        val time: TextView = v.findViewById(R.id.ap_time)
        val text: TextView = v.findViewById(R.id.ap_text)
        val suggestions: LinearLayout = v.findViewById(R.id.ap_suggestions)
        val skip: MaterialButton = v.findViewById(R.id.ap_skip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_approval, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.chat.text = if (a.isGroup) "${a.sender} · ${a.chatName}" else a.chatName
        h.time.text = timeFmt.format(Date(a.time))
        h.text.text = a.text
        h.suggestions.removeAllViews()
        val ctx = h.itemView.context
        a.suggestions.forEach { s ->
            val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = s
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { onPick(a, s) }
            }
            h.suggestions.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        h.skip.setOnClickListener { onSkip(a) }
    }
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
            "auto_replied" -> "Auto-replied" to R.color.act_replied
            "replied" -> "You replied" to R.color.act_replied
            "suggested" -> "Suggested" to R.color.act_noted
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
