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
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
        private const val MAX_DOWNLOAD = 250_000_000L
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
