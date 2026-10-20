package com.hasaan.busymode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Incoming messages with AI reply suggestions waiting for the user's approval. */
object ApprovalStore {
    data class Item(
        val id: String,
        val chatJid: String,
        val chatName: String,
        val sender: String,
        val isGroup: Boolean,
        val text: String,
        val suggestions: List<String>,
        val reason: String,
        val time: Long
    ) {
        /** Small stable number for this item's notification. */
        val notificationId: Int get() = 10_000 + (id.hashCode() and 0x7ffff)

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("chatJid", chatJid).put("chatName", chatName).put("sender", sender)
            .put("isGroup", isGroup).put("text", text).put("suggestions", JSONArray(suggestions))
            .put("reason", reason).put("time", time)

        companion object {
            fun from(o: JSONObject): Item {
                val arr = o.optJSONArray("suggestions") ?: JSONArray()
                return Item(
                    o.optString("id"), o.optString("chatJid"), o.optString("chatName"), o.optString("sender"),
                    o.optBoolean("isGroup"), o.optString("text"),
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() },
                    o.optString("reason"), o.optLong("time", System.currentTimeMillis())
                )
            }
        }
    }

    private const val MAX_AGE_MS = 24L * 3600 * 1000
    private val items = LinkedHashMap<String, Item>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "approvals.json")
        file = f
        if (f.exists()) try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) Item.from(arr.getJSONObject(i)).let { items[it.id] = it }
        } catch (_: Exception) {}
        prune()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        synchronized(this) { items.values.removeAll { it.time < cutoff } }
    }

    fun all(): List<Item> {
        prune()
        return synchronized(this) { items.values.sortedByDescending { it.time } }
    }

    fun get(id: String): Item? = synchronized(this) { items[id] }

    fun put(item: Item) {
        synchronized(this) { items[item.id] = item }
        changed()
    }

    fun remove(id: String): Item? {
        val removed = synchronized(this) { items.remove(id) }
        if (removed != null) changed()
        return removed
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun changed() {
        val snapshot = synchronized(this) { JSONArray(items.values.map { it.toJson() }).toString() }
        file?.let { f -> io.execute { try { f.writeText(snapshot) } catch (_: Exception) {} } }
        listeners.forEach { it() }
    }
}
