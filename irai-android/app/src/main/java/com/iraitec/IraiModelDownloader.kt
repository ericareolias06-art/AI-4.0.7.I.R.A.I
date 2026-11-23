package com.iraitec

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pinned public publisher model. Its SHA-256 is verified before installation.
 * The user must consent to the large download. No cloud mode choice is exposed.
 */
class IraiModelDownloader(private val context: Context, private val local: IraiLocalInference) {
    data class Result(val success: Boolean, val detail: String)
    companion object {
        const val FILE_NAME = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm"
        const val EXPECTED_SHA256 = "97e6a9700208f59fce2ceb993cd6c18ea77ada8c2a940f68332004f38a404671"
        const val DOWNLOAD_URL =
            "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/b830c10c762dcc56e013a1ee25bef8bf6fa0dc1d/" +
            FILE_NAME + "?download=true"
        const val APPROX_BYTES = 736_000_000L
        private const val MAX_BYTES = 1_200_000_000L
    }

    private val capabilities = IraiDeviceCapabilities(context)

    @Volatile var cancelled: Boolean = false
    @Volatile var running: Boolean = false
        private set

    fun cancel() { cancelled = true }

    /** Call on a background thread only. Keeps partial download for restart. */
    fun download(onProgress: (Int) -> Unit): Result {
        if (running) return Result(false, "A model download is already active.")
        running = true
        cancelled = false
        val partial = File(context.filesDir, "irai-model-download.part")
        try {
            val preflight = capabilities.checkBeforeDownload(APPROX_BYTES, partial.length())
            if (!preflight.supported) return Result(false, preflight.reason ?: "Device cannot install this AI model.")
            var offset = if (partial.exists()) partial.length() else 0L
            if (offset > MAX_BYTES) {
                partial.delete()
                offset = 0
            }
            val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 35_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IraiTec-Android/1.0")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    return Result(false, "Model download server returned HTTP $status. Check internet and retry.")
                }
                val rangeStart = connection.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                val resumed = status == HttpURLConnection.HTTP_PARTIAL &&
                    offset > 0L && rangeStart == offset
                if (status == HttpURLConnection.HTTP_PARTIAL && !resumed) {
                    return Result(false, "The model server sent an invalid resume range. Retry.")
                }
                if (offset > 0L && !resumed) {
                    partial.delete()
                    offset = 0L
                }
                val total = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull()
                    ?: if (connection.contentLengthLong > 0) connection.contentLengthLong + offset else APPROX_BYTES
                if (total > MAX_BYTES) return Result(false, "Unexpected model size. Download stopped.")
                connection.inputStream.buffered().use { incoming ->
                    // Append when resuming. Do not delete previous verified bytes.
                    java.io.FileOutputStream(partial, resumed).buffered().use { out ->
                        val bytes = ByteArray(128 * 1024)
                        var written = offset
                        while (true) {
                            if (cancelled) return Result(false, "Download paused. Open Irai again to resume.")
                            val count = incoming.read(bytes)
                            if (count < 0) break
                            written += count
                            if (written > MAX_BYTES) return Result(false, "Model download exceeded the allowed size.")
                            out.write(bytes, 0, count)
                            onProgress(((written * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (cancelled) return Result(false, "Download paused.")
            val sha = MessageDigest.getInstance("SHA-256")
            partial.inputStream().buffered().use { input ->
                val bytes = ByteArray(1024 * 1024)
                while (true) {
                    if (cancelled) return Result(false, "Verification paused.")
                    val count = input.read(bytes)
                    if (count < 0) break
                    sha.update(bytes, 0, count)
                }
            }
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (actual != EXPECTED_SHA256) {
                partial.delete()
                return Result(false, "Model verification failed. Retry the download.")
            }
            if (!local.installModel(partial)) {
                return Result(false, "Model downloaded but installation failed. Check storage and retry.")
            }
            context.getSharedPreferences("irai_local_ai", Context.MODE_PRIVATE)
                .edit().putBoolean("local_first", true).putBoolean("gpu_model", true).apply()
            onProgress(100)
            return Result(true, "Irai's on-device model is installed and verified.")
        } catch (error: Exception) {
            Log.w("IRAI_MODEL", "Download interrupted", error)
            return Result(false, "Download interrupted. Check internet and storage, then retry.")
        } finally {
            running = false
        }
    }
}
