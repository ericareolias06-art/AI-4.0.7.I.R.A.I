package com.iraitec

import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * Reads one authenticated POST response as SSE.
 * All callbacks are invoked on the calling (background) thread.
 * The caller is responsible for marshaling UI work to the main thread.
 */
internal object IraiStreamingChat {
    data class Result(val reply: String, val success: Boolean, val unsupported: Boolean = false)

    fun read(connection: HttpURLConnection, payload: String, onDelta: (String) -> Unit): Result {
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 150000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 404) return Result("", false, unsupported = true)
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = try {
                    JSONObject(body).optString("message", "Sorry, I couldn't reach my AI service.")
                } catch (_: Exception) {
                    "Sorry, I couldn't reach my AI service."
                }
                return Result(message, false)
            }
            val complete = StringBuilder()
            var ended = false
            var failure: String? = null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var event = ""
                val data = StringBuilder()
                fun dispatch() {
                    if (data.isEmpty() && event.isEmpty()) return
                    val json = try { JSONObject(data.toString()) } catch (_: Exception) { JSONObject() }
                    when (event) {
                        "delta" -> {
                            val piece = json.optString("text", "")
                            if (piece.isNotEmpty()) {
                                complete.append(piece)
                                onDelta(piece)
                            }
                        }
                        "done" -> ended = true
                        "error" -> failure = json.optString("message", "Irai couldn't finish her reply.")
                    }
                    event = ""
                    data.setLength(0)
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        dispatch()
                        if (ended || failure != null) break
                    } else if (line.startsWith("event:")) {
                        event = line.substringAfter(":").trim()
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substringAfter(":").trimStart())
                    }
                }
            }
            if (failure != null) return Result(failure!!, false)
            if (!ended || complete.isEmpty()) {
                return Result("Irai's reply was interrupted. Please try again.", false)
            }
            return Result(complete.toString(), true)
        } finally {
            connection.disconnect()
        }
    }
}
