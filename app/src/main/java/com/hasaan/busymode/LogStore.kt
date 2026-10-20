package com.hasaan.busymode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Keeps the most recent messages and what the assistant did with them, saved to a small file. */
object LogStore {
    data class Entry(
        val time: Long,
        val chatName: String,
        val sender: String,
        val isGroup: Boolean,
        val text: String,
        val action: String,
        val reason: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("time", time).put("chatName", chatName).put("sender", sender).put("isGroup", isGroup)
            .put("text", text).put("action", action).put("reason", reason)

        companion object {
            fun from(o: JSONObject) = Entry(
                o.optLong("time", System.currentTimeMillis()),
                o.optString("chatName"),
                o.optString("sender"),
                o.optBoolean("isGroup"),
                o.optString("text"),
                o.optString("action"),
                o.optString("reason")
            )
        }
    }

    private const val MAX = 300
    private val entries = ArrayList<Entry>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "log.json")
        file = f
        if (f.exists()) try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) entries.add(Entry.from(arr.getJSONObject(i)))
        } catch (_: Exception) {}
    }

    fun all(): List<Entry> = synchronized(this) { entries.toList().asReversed() }

    fun add(e: Entry) {
        synchronized(this) {
            entries.add(e)
            while (entries.size > MAX) entries.removeAt(0)
        }
        save()
        listeners.forEach { it() }
    }

    fun clear() {
        synchronized(this) { entries.clear() }
        save()
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun save() {
        val snapshot = synchronized(this) { JSONArray(entries.map { it.toJson() }).toString() }
        val f = file ?: return
        io.execute { try { f.writeText(snapshot) } catch (_: Exception) {} }
    }
}
