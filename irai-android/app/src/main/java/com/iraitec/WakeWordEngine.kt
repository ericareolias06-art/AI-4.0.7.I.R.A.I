package com.iraitec

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

internal class WakeWordEngine(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {}
) : AutoCloseable {

    companion object {
        private const val TAG = "IraiWakeDebug"
        private const val RATE = 16000
        private const val MODEL_DIR = "irai_kws"
    }

    private val spotter: KeywordSpotter
    private val stream: OnlineStream

    private var lastTriggerMs = 0L
    private var inferenceCount = 0
    private var closed = false

    init {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            ),
            tokens = "$MODEL_DIR/tokens.txt",
            numThreads = 2,
            modelType = "zipformer2"
        )

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = RATE,
                featureDim = 80
            ),
            modelConfig = modelConfig,
            maxActivePaths = 4,
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2
        )

        spotter = KeywordSpotter(context.assets, config)
        stream = spotter.createStream()

        report("MODEL READY: Sherpa Irai wake-word model loaded.")
    }

    private fun report(message: String) {
        Log.d(TAG, message)
        try {
            onDiagnostic(message)
        } catch (e: Exception) {
            Log.w(TAG, "Could not send diagnostic", e)
        }
    }

    @Synchronized
    fun accept(chunk: FloatArray): Boolean {
        if (closed || chunk.isEmpty()) return false

        stream.acceptWaveform(chunk, RATE)

        while (spotter.isReady(stream)) {
            spotter.decode(stream)

            val keyword = spotter.getResult(stream).keyword

            if (keyword.isNotBlank()) {
                spotter.reset(stream)

                val now = SystemClock.elapsedRealtime()

                if (now - lastTriggerMs > 700) {
                    lastTriggerMs = now
                    report("DETECTED: Irai recognized! Keyword: $keyword")
                    return true
                }
            }
        }

        inferenceCount++

        if (inferenceCount % 50 == 0) {
            report("KWS RUNNING: Listening for Irai.")
        }

        return false
    }

    @Synchronized
    override fun close() {
        if (closed) return

        closed = true
        stream.release()
        spotter.release()

        Log.d(TAG, "Wake-word engine released.")
    }
}
