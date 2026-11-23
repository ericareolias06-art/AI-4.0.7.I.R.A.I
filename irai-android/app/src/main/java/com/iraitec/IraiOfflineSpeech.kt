package com.iraitec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Fully offline command transcription for Android 7-11.
 *
 * Requires a separately installed Sherpa streaming English model in
 * filesDir/irai_asr with encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx, decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx,
 * joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx, and tokens.txt. Never invokes Android's cloud recognizer.
 * Call on a background thread after stopping wake-word microphone capture.
 */
internal class IraiOfflineSpeech(private val context: Context) {
    private val directory = File(context.filesDir, "irai_asr")
    private val required = listOf("encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx", "decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx", "joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx", "tokens.txt")

    fun isInstalled(): Boolean = required.all { File(directory, it).isFile && File(directory, it).length() > 0 }

    fun recognize(maxDurationMs: Long = 15_000): String {
        require(isInstalled()) { "Offline English speech model is not installed." }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission required"
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(directory, "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx").absolutePath,
                    decoder = File(directory, "decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx").absolutePath,
                    joiner = File(directory, "joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx").absolutePath
                ),
                tokens = File(directory, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )
        val recognizer = OnlineRecognizer(config = config)
        val stream = recognizer.createStream()
        val minBuffer = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Microphone unavailable" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 3200)
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(1600)
            val start = android.os.SystemClock.elapsedRealtime()
            recorder.startRecording()
            while (android.os.SystemClock.elapsedRealtime() - start < maxDurationMs) {
                val n = recorder.read(samples, 0, samples.size)
                if (n <= 0) break
                stream.acceptWaveform(FloatArray(n) { samples[it] / 32768f }, 16000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream) && recognizer.getResult(stream).text.isNotBlank()) break
            }
            return recognizer.getResult(stream).text.trim()
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            stream.release()
            recognizer.release()
        }
    }
}
