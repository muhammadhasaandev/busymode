package com.hasaan.busymode

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

/**
 * The assistant's screen: what you said, the answer, and tappable numbered options
 * (contacts, reply options, a message to confirm). Works over the lock screen.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var level: LinearProgressIndicator
    private lateinit var user: TextView
    private lateinit var reply: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardDetail: TextView
    private lateinit var options: LinearLayout
    private lateinit var confirm: View
    private lateinit var hint: TextView
    private lateinit var mic: ExtendedFloatingActionButton
    private lateinit var input: TextInputEditText
    private lateinit var scroll: NestedScrollView

    private val uiListener: () -> Unit = { render() }
    private val levelListener: () -> Unit = { level.setProgressCompat((AssistantUi.level * 100).toInt(), true) }
    private val autoClose = Runnable {
        if (!(VoiceAssistant.instance?.isActive ?: false) && !hasOpenChoice()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_assistant)

        dot = findViewById(R.id.as_dot)
        status = findViewById(R.id.as_status)
        level = findViewById(R.id.as_level)
        user = findViewById(R.id.as_user)
        reply = findViewById(R.id.as_reply)
        cardTitle = findViewById(R.id.as_card_title)
        cardDetail = findViewById(R.id.as_card_detail)
        options = findViewById(R.id.as_options)
        confirm = findViewById(R.id.as_confirm)
        hint = findViewById(R.id.as_hint)
        mic = findViewById(R.id.as_mic)
        input = findViewById(R.id.as_input)
        scroll = findViewById(R.id.as_scroll)

        findViewById<View>(R.id.as_root).setOnClickListener { close() }
        findViewById<ImageButton>(R.id.as_close).setOnClickListener { close() }
        findViewById<MaterialButton>(R.id.as_send).setOnClickListener { submit("Yes, send it.") }
        findViewById<MaterialButton>(R.id.as_cancel).setOnClickListener { submit("No, cancel it.") }
        mic.setOnClickListener {
            val va = VoiceAssistant.instance ?: return@setOnClickListener
            if (VoiceAssistant.state == VoiceAssistant.State.LISTENING) va.finishNow() else va.listenAgain()
        }
        findViewById<TextInputLayout>(R.id.as_input_layout).setEndIconOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
    }

    override fun onStart() {
        super.onStart()
        AssistantUi.addListener(uiListener)
        AssistantUi.addLevelListener(levelListener)
        VoiceAssistant.addStateListener(uiListener)
        render()
    }

    override fun onStop() {
        AssistantUi.removeListener(uiListener)
        AssistantUi.removeLevelListener(levelListener)
        VoiceAssistant.removeStateListener(uiListener)
        dot.removeCallbacks(autoClose)
        super.onStop()
    }

    private fun close() {
        VoiceAssistant.instance?.cancel()
        finish()
    }

    private fun submit(text: String) {
        val va = VoiceAssistant.instance ?: return
        va.submitText(text)
    }

    private fun sendTyped() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input.setText("")
        submit(t)
    }

    private fun hasOpenChoice(): Boolean =
        AssistantUi.card?.optString("type") in setOf("contacts", "replies", "confirm")

    private fun render() {
        val st = VoiceAssistant.state
        val (label, color) = when (st) {
            VoiceAssistant.State.PROMPTING, VoiceAssistant.State.SPEAKING -> "Speaking…" to R.color.act_noted
            VoiceAssistant.State.LISTENING -> "Listening…" to R.color.ok
            VoiceAssistant.State.THINKING -> "Thinking…" to R.color.warn
            VoiceAssistant.State.ERROR -> (VoiceAssistant.detail.ifBlank { "Problem" }) to R.color.bad
            else -> "Assistant" to R.color.muted
        }
        status.text = label
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        level.visibility = if (st == VoiceAssistant.State.LISTENING) View.VISIBLE else View.INVISIBLE

        mic.text = if (st == VoiceAssistant.State.LISTENING) "Done" else "Talk"

        user.text = AssistantUi.userText
        user.visibility = if (AssistantUi.userText.isBlank()) View.GONE else View.VISIBLE
        reply.text = AssistantUi.replyText
        hint.text = AssistantUi.hint
        hint.visibility = if (AssistantUi.hint.isBlank()) View.GONE else View.VISIBLE
        renderCard(AssistantUi.card)

        dot.removeCallbacks(autoClose)
        val idle = st == VoiceAssistant.State.WAITING || st == VoiceAssistant.State.OFF
        if (idle && !hasOpenChoice()) dot.postDelayed(autoClose, 8000)
    }

    private fun renderCard(card: JSONObject?) {
        options.removeAllViews()
        confirm.visibility = View.GONE
        if (card == null) {
            cardTitle.visibility = View.GONE; cardDetail.visibility = View.GONE
            return
        }
        val type = card.optString("type")
        cardTitle.text = card.optString("title")
        cardTitle.visibility = if (cardTitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val detail = card.optString("detail")
        cardDetail.text = detail
        cardDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

        when (type) {
            "contacts", "replies" -> {
                val items = card.optJSONArray("items") ?: return
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val n = it.optInt("option", i + 1)
                    val enabled = it.optBoolean("enabled", true)
                    addOption(n.toString(), it.optString("name"), it.optString("detail"), it.optString("preview"), enabled) {
                        submit("Option $n")
                    }
                }
            }
            "confirm" -> {
                addOption("✉", card.optString("text"), "", "", true, null)
                confirm.visibility = View.VISIBLE
            }
            "messages" -> {
                val items = card.optJSONArray("items") ?: return
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val sender = it.optString("sender")
                    val chat = it.optString("chat")
                    val head = if (sender == chat || chat.startsWith(sender)) chat else "$chat · $sender"
                    addOption("${i + 1}", head, it.optString("time"), it.optString("text"), true, null)
                }
            }
            "done" -> addOption("✓", card.optString("text"), "", "", true, null)
        }
    }

    private fun addOption(num: String, name: String, detail: String, preview: String, enabled: Boolean, onTap: (() -> Unit)?) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_option, options, false)
        v.findViewById<TextView>(R.id.op_num).text = num
        v.findViewById<TextView>(R.id.op_name).text = name
        v.findViewById<TextView>(R.id.op_detail).apply { text = detail; visibility = if (detail.isBlank()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.op_preview).apply { text = preview; visibility = if (preview.isBlank()) View.GONE else View.VISIBLE }
        v.alpha = if (enabled) 1f else 0.5f
        if (onTap != null && enabled) v.setOnClickListener { onTap() } else v.isClickable = false
        options.addView(v)
    }
}
