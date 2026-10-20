package com.hasaan.busymode

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** What the assistant screen shows: last thing you said, the answer, and any options card. */
object AssistantUi {
    @Volatile var userText: String = ""
        private set
    @Volatile var replyText: String = ""
        private set
    @Volatile var hint: String = ""
        private set
    @Volatile var card: JSONObject? = null
        private set
    /** Microphone loudness 0..1 while listening. */
    @Volatile var level: Float = 0f
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val levelListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun addLevelListener(l: () -> Unit) = levelListeners.add(l)
    fun removeLevelListener(l: () -> Unit) = levelListeners.remove(l)

    private fun changed() = main.post { listeners.forEach { it() } }

    fun newConversation() {
        userText = ""; replyText = ""; hint = ""; card = null
        changed()
    }

    fun setUser(text: String) { userText = text; hint = ""; changed() }

    fun setReply(text: String, ui: JSONObject?) {
        replyText = text
        card = ui
        hint = ""
        changed()
    }

    fun setHint(text: String) { hint = text; changed() }

    fun setLevel(v: Float) {
        level = v
        main.post { levelListeners.forEach { it() } }
    }
}
