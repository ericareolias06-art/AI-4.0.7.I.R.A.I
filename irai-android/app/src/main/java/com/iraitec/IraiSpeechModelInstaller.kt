package com.iraitec

import android.content.Context
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the official Sherpa English streaming ASR archive.
 * Explicit consent is required by the caller. The archive is downloaded
 * over HTTPS and only allowlisted files are extracted, with size limits.
 * A publisher-provided SHA-256 should be pinned before production release.
 */
internal class IraiSpeechModelInstaller(private val context: Context) {
    companion object {
        private const val URL_STRING =
            "https://huggingface.co/xumo/onnx_models/resolve/a2698dfcc0315eec55427270c093844814d93ee4/" +
            "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2?download=true"
        private const val MAX_DOWNLOAD = 330_000_000L
        // Pinned to the Git LFS SHA-256 recorded in the immutable mirror commit.
        private const val EXPECTED_SHA256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282"
        private const val MAX_EXTRACTED = 400_000_000L
        private val ALLOWED = setOf(
            "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            "tokens.txt"
        )
    }

    fun install(): Boolean {
        val destination = File(context.filesDir, "irai_asr")
        val staging = File(context.cacheDir, "irai-asr-staging")
        val archive = File(context.cacheDir, "irai-asr-download.tar.bz2")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val connection = URL(URL_STRING).openConnection() as HttpURLConnection
            connection.connectTimeout = 25_000
            connection.readTimeout = 45_000
            try {
                check(connection.responseCode == 200) { "ASR model download failed" }
                var downloaded = 0L
                connection.inputStream.use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            downloaded += n
                            check(downloaded <= MAX_DOWNLOAD) { "ASR archive exceeds size limit" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } finally { connection.disconnect() }
            val sha = MessageDigest.getInstance("SHA-256")
            archive.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sha.update(buffer, 0, n)
                }
            }
            val actualSha = sha.digest().joinToString("") { "%02x".format(it) }
            check(actualSha.equals(EXPECTED_SHA256, ignoreCase = true)) {
                "Speech model SHA-256 verification failed; refusing to install"
            }
            var totalExtracted = 0L
            archive.inputStream().buffered().use { input ->
                TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (!entry.isFile) continue
                        val name = entry.name.substringAfterLast('/')
                        if (name !in ALLOWED) continue
                        check(entry.size >= 0 && entry.size <= MAX_EXTRACTED)
                        totalExtracted += entry.size
                        check(totalExtracted <= MAX_EXTRACTED)
                        val target = File(staging, name)
                        target.outputStream().use { output -> tar.copyTo(output) }
                    }
                }
            }
            check(ALLOWED.all { File(staging, it).isFile && File(staging, it).length() > 0L }) {
                "ASR archive is missing required model files"
            }
            // Do not replace a working model until extraction has completed.
            val backup = File(context.filesDir, "irai_asr.backup")
            backup.deleteRecursively()
            if (destination.exists()) check(destination.renameTo(backup))
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                return false
            }
            backup.deleteRecursively()
            return true
        } catch (error: Exception) {
            android.util.Log.e("IRAI_ASR", "Could not install offline speech model", error)
            return false
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}
