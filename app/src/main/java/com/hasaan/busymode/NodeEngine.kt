package com.hasaan.busymode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Runs the JavaScript engine (Baileys + Groq) on an embedded Node.js runtime and talks to it
 * over a private localhost socket. Node can only be started once per app process, so this is
 * a process-wide singleton.
 */
object NodeEngine {
    private const val TAG = "NodeEngine"

    data class Status(val state: String = "starting", val registered: Boolean = false, val me: String? = null)

    interface Listener {
        fun onStatus(status: Status) {}
        fun onPairingCode(code: String) {}
        fun onError(message: String) {}
        fun onAnnounce(text: String) {}
        fun onEvent(entry: JSONObject) {}
        fun onConnected() {}
        fun onApproval(item: JSONObject) {}
        fun onApprovalDone(id: String, status: String) {}
        fun onSay(session: Int, text: String, listen: Boolean) {}
    }

    @Volatile var status = Status()
        private set
    @Volatile var pairingCode: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val queue = ArrayList<String>()
    private var started = false
    private var token = ""
    @Volatile private var out: OutputStreamWriter? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    private external fun startNode(args: Array<String>): Int

    /** Last startup failure, shown to the user instead of crashing the app. */
    @Volatile var startupError: String? = null
        private set

    /** Starts the engine once. Never throws: a failure is reported through [onError] instead. */
    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (started) return startupError == null
        started = true
        val app = context.applicationContext
        // Start off the main thread (sockets and copying files are not allowed on it).
        Thread({
            try {
                startInternal(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Engine failed to start", t)
                val msg = "The WhatsApp engine could not start on this phone (${t.javaClass.simpleName}: ${t.message}). Please send a screenshot of this message."
                startupError = msg
                main.post { listeners.forEach { it.onError(msg) } }
            }
        }, "engine-start").start()
        return true
    }

    private fun startInternal(app: Context) {
        // Android has no /tmp; give Node a writable temp folder and home.
        try {
            android.system.Os.setenv("TMPDIR", app.cacheDir.absolutePath, true)
            android.system.Os.setenv("HOME", app.filesDir.absolutePath, true)
        } catch (_: Exception) {}
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("busymode-native")

        val projectDir = copyEngine(app)
        val dataDir = File(app.filesDir, "engine-data").apply { mkdirs() }
        token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        Thread({ acceptLoop(server) }, "engine-bridge").apply { isDaemon = true; start() }

        val args = arrayOf("node", File(projectDir, "main.mjs").absolutePath, server.localPort.toString(), token, dataDir.absolutePath)
        Thread(null, {
            val code = try { startNode(args) } catch (t: Throwable) { Log.e(TAG, "Node crashed", t); -1 }
            Log.e(TAG, "Node exited with code $code")
            main.post { listeners.forEach { it.onError("The engine stopped unexpectedly. Please restart the app.") } }
        }, "nodejs", 16L * 1024 * 1024).start()
    }

    /** Copy the bundled engine from the APK to internal storage when the app is installed or updated. */
    private fun copyEngine(context: Context): File {
        val dir = File(context.filesDir, "nodejs-project")
        val stamp = File(dir, ".version")
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${PackageInfoCompat.getLongVersionCode(pi)}-${pi.lastUpdateTime}"
        } catch (e: PackageManager.NameNotFoundException) { "0" }
        if (stamp.exists() && stamp.readText() == version) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        context.assets.list("nodejs-project")!!.forEach { name ->
            context.assets.open("nodejs-project/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        stamp.writeText(version)
        return dir
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val sock = try { server.accept() } catch (e: Exception) { continue }
            Thread({ handleClient(sock) }, "engine-client").apply { isDaemon = true; start() }
        }
    }

    private fun handleClient(sock: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val hello = JSONObject(reader.readLine() ?: return)
            if (hello.optString("t") != "hello" || hello.optString("token") != token) {
                sock.close(); return
            }
            val w = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            synchronized(queue) {
                out = w
            }
            main.post { listeners.forEach { it.onConnected() } }
            // Flush anything the app wanted to say before the engine was ready.
            flushQueue()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try { JSONObject(line) } catch (e: Exception) { continue }
                main.post { dispatch(msg) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bridge connection ended: ${e.message}")
        } finally {
            synchronized(queue) { out = null }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun dispatch(msg: JSONObject) {
        when (msg.optString("t")) {
            "status" -> {
                status = Status(msg.optString("state"), msg.optBoolean("registered"), msg.optString("me").takeIf { it.isNotBlank() && it != "null" })
                if (status.state == "connected") pairingCode = null
                listeners.forEach { it.onStatus(status) }
            }
            "pairing_code" -> {
                val code = msg.optString("code")
                pairingCode = code
                listeners.forEach { it.onPairingCode(code) }
            }
            "announce" -> listeners.forEach { it.onAnnounce(msg.optString("text")) }
            "event" -> msg.optJSONObject("entry")?.let { e -> listeners.forEach { it.onEvent(e) } }
            "error" -> listeners.forEach { it.onError(msg.optString("message")) }
            "approval" -> msg.optJSONObject("item")?.let { item -> listeners.forEach { it.onApproval(item) } }
            "approval_done" -> listeners.forEach { it.onApprovalDone(msg.optString("id"), msg.optString("status")) }
            "say" -> listeners.forEach { it.onSay(msg.optInt("session"), msg.optString("text"), msg.optBoolean("listen")) }
            "log" -> Log.i(TAG, msg.optString("message"))
        }
    }

    /** Send a command to the engine. Queued if the engine isn't connected yet. */
    fun send(cmd: JSONObject) {
        val line = cmd.toString() + "\n"
        synchronized(queue) {
            queue.add(line)
            if (queue.size > 50) queue.removeAt(0)
        }
        flushQueue()
    }

    private fun flushQueue() {
        writer.execute {
            while (true) {
                val (w, line) = synchronized(queue) {
                    val w = out ?: return@execute
                    if (queue.isEmpty()) return@execute
                    w to queue.removeAt(0)
                }
                try {
                    w.write(line); w.flush()
                } catch (e: Exception) {
                    synchronized(queue) { queue.add(0, line) }
                    return@execute
                }
            }
        }
    }

    fun sendConfig(settings: Settings) = send(JSONObject().put("t", "config").put("config", settings.toEngineConfig()))
    fun start() = send(JSONObject().put("t", "start"))
    fun stop() = send(JSONObject().put("t", "stop"))
    fun pair(phone: String) = send(JSONObject().put("t", "pair").put("phone", phone))
    fun logout() = send(JSONObject().put("t", "logout"))
    fun approve(id: String, chatJid: String, text: String) =
        send(JSONObject().put("t", "approve").put("id", id).put("chatJid", chatJid).put("text", text))
    fun reject(id: String) = send(JSONObject().put("t", "reject").put("id", id))
    fun voice(session: Int, text: String) = send(JSONObject().put("t", "voice").put("session", session).put("text", text))
    fun contacts(list: org.json.JSONArray) = send(JSONObject().put("t", "contacts").put("list", list))
}
