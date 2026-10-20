package com.hasaan.busymode

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Speech-to-text with Groq's hosted Whisper model. */
object GroqStt {
    private const val URL_STT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    class SttException(message: String, val status: Int) : Exception(message)

    /** Wrap 16-bit mono PCM in a WAV header. */
    fun wav(pcm: ShortArray, length: Int, sampleRate: Int = 16000): ByteArray {
        val dataBytes = length * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataBytes)
        for (i in 0 until length) buf.putShort(pcm[i])
        return buf.array()
    }

    /** Returns the transcript (may be empty). Runs network I/O: call off the main thread. */
    fun transcribe(apiKey: String, wavBytes: ByteArray, prompt: String): String {
        val boundary = "----busymode${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.write(value.toByteArray(Charsets.UTF_8)); out.writeBytes("\r\n")
        }
        field("model", MODEL)
        field("response_format", "json")
        field("temperature", "0")
        if (prompt.isNotBlank()) field("prompt", prompt.take(400))
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        out.write(wavBytes); out.writeBytes("\r\n--$boundary--\r\n")
        out.flush()

        val conn = URL(URL_STT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw SttException("Speech recognition failed ($code)", code)
            return JSONObject(text).optString("text").trim()
        } finally {
            conn.disconnect()
        }
    }
}
