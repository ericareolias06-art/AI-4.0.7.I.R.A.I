
package com.iraitec

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming 16 kHz wake-word detector.
 * Input: mono PCM audio normalized to [-1, 1].
 * Model input: [1, 40, 101], output: "logit".
 */
internal class WakeWordEngine(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {}
) : AutoCloseable {

    companion object {
        private const val TAG = "IraiWakeDebug"
        private const val RATE = 16000
        private const val WINDOW_SIZE = RATE
        private const val FFT_SIZE = 512
        private const val HOP = 160
        private const val FRAMES = 101
        private const val MELS = 40
        private const val THRESHOLD = 0.613f
    }

    private data class Sos(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
        val z1: Double, val z2: Double
    )

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val audioWindow = FloatArray(WINDOW_SIZE)
    private var audioCount = 0
    private var samplesSinceInference = 0
    private var aboveCount = 0
    private var lastTriggerMs = 0L
    private var inferenceCount = 0
    private var rejectedCount = 0

    private val filters = arrayOf(
        arrayOf(
            Sos(.9042459858119793, -1.8084919716239587, .9042459858119793,
                -1.9243597360235134, .9258444923331056,
                -.9042459858119207, .9042459858119251),
            Sos(1.0, -2.0, 1.0, -1.935283933705594, .9367771186738887,
                0.0, 0.0),
            Sos(1.0, -2.0, 1.0, -1.9557989964479017, .9573080099893462,
                0.0, 0.0),
            Sos(1.0, -2.0, 1.0, -1.9832678076605255, .9847980149995752,
                0.0, 0.0)
        ),
        arrayOf(
            Sos(.9996728578106041, -1.9989603227048798, .9996728578106046,
                -1.9989603227048796, .9993457156212082,
                .00032714218982763185, .0003271421889648693)
        ),
        arrayOf(
            Sos(.9996074550501642, -1.9986599884535616, .9996074550501639,
                -1.9986599884535616, .999214910100328,
                .0003925449500836464, .0003925449495881815)
        )
    )

    private val filterStates = filters.map { sections ->
        Array(sections.size) { doubleArrayOf(0.0, 0.0) }
    }

    private var filterInitialized = false
    private val mel = FloatArray(MELS * FRAMES)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val power = DoubleArray(FFT_SIZE / 2 + 1)

    private val lpAlpha = 0.936
    private var lpState = 0.0

    init {
        val modelBytes =
            context.assets.open("wake.onnx").use { it.readBytes() }

        val options = OrtSession.SessionOptions()
        session = try {
            env.createSession(modelBytes, options)
        } finally {
            options.close()
        }

        report("MODEL READY: Wake-word model loaded.")
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
        if (chunk.isEmpty()) return false

        val filtered = FloatArray(chunk.size)

        for (i in chunk.indices) {
            var x = chunk[i].toDouble()

            if (!filterInitialized) {
                for (stage in filters.indices) {
                    for (sectionIndex in filters[stage].indices) {
                        val s = filters[stage][sectionIndex]
                        val state = filterStates[stage][sectionIndex]
                        state[0] = s.z1 * x
                        state[1] = s.z2 * x
                    }
                }
                filterInitialized = true
            }

            for (stage in filters.indices) {
                for (sectionIndex in filters[stage].indices) {
                    val s = filters[stage][sectionIndex]
                    val state = filterStates[stage][sectionIndex]

                    val y = s.b0 * x + state[0]
                    state[0] = s.b1 * x - s.a1 * y + state[1]
                    state[1] = s.b2 * x - s.a2 * y
                    x = y
                }
            }

            filtered[i] = x.toFloat()
        }

        if (filtered.size >= WINDOW_SIZE) {
            filtered.copyInto(
                audioWindow,
                0,
                filtered.size - WINDOW_SIZE,
                filtered.size
            )
            audioCount = WINDOW_SIZE
        } else {
            val shift = filtered.size
            audioWindow.copyInto(audioWindow, 0, shift, WINDOW_SIZE)
            filtered.copyInto(audioWindow, WINDOW_SIZE - shift)
            audioCount = minOf(WINDOW_SIZE, audioCount + shift)
        }

        samplesSinceInference += chunk.size

        if (audioCount < WINDOW_SIZE ||
            samplesSinceInference < HOP * 10
        ) {
            return false
        }

        samplesSinceInference %= HOP * 10

        if (!passesEnergyGate()) {
            aboveCount = 0
            rejectedCount++

            if (rejectedCount % 10 == 0) {
                report(
                    "AUDIO GATE: Sound is too quiet or does not meet " +
                    "the audio filter. Rejected $rejectedCount windows."
                )
            }

            return false
        }

        computeMel()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(mel),
            longArrayOf(1, MELS.toLong(), FRAMES.toLong())
        )

        val probability = try {
            session.run(mapOf("mel" to tensor)).use { result ->
                val value =
                    (result["logit"].orElseThrow().value as FloatArray)[0]

                (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
            }
        } finally {
            tensor.close()
        }

        inferenceCount++

        if (probability > THRESHOLD) {
            aboveCount++
        } else {
            aboveCount = 0
        }

        // Show the probability on the phone periodically.
        if (inferenceCount % 5 == 0) {
            report(
                "MODEL SCORE: ${"%.3f".format(java.util.Locale.US, probability)}" +
                " | Threshold: $THRESHOLD" +
                " | Consecutive passes: $aboveCount"
            )
        }

        val now = android.os.SystemClock.elapsedRealtime()

        if (aboveCount >= 2 && now - lastTriggerMs > 700) {
            aboveCount = 0
            lastTriggerMs = now

            report(
                "DETECTED: Irai recognized! Score: " +
                "%.3f".format(java.util.Locale.US, probability)
            )

            return true
        }

        return false
    }

    private fun passesEnergyGate(): Boolean {
        var sumSq = 0.0
        var bandSq = 0.0

        for (sample in audioWindow) {
            val x = sample.toDouble()
            sumSq += x * x
            lpState = (1.0 - lpAlpha) * x + lpAlpha * lpState
            bandSq += lpState * lpState
        }

        val rms = sqrt(sumSq / WINDOW_SIZE)
        val db =
            if (rms < 1e-9) -120.0
            else 20.0 * ln(rms) / ln(10.0)

        val fraction = bandSq / (sumSq + 1e-12)

        return db >= -55.0 && fraction >= 0.15
    }

    private fun computeMel() {
        for (frame in 0 until FRAMES) {
            val start = frame * HOP - FFT_SIZE / 2
            re.fill(0.0)
            im.fill(0.0)

            for (n in 0 until FFT_SIZE) {
                var index = start + n

                if (index < 0) {
                    index = -index
                } else if (index >= WINDOW_SIZE) {
                    index = 2 * (WINDOW_SIZE - 1) - index
                }

                index = index.coerceIn(0, WINDOW_SIZE - 1)

                val w = if (n in 56 until 456) {
                    WakeCoefficients.hannWindow[n - 56].toDouble()
                } else {
                    0.0
                }

                re[n] = audioWindow[index].toDouble() * w
            }

            fft()

            for (k in power.indices) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }

            for (m in 0 until MELS) {
                var acc = 0.0
                val bins = WakeCoefficients.melBins[m]
                val weights = WakeCoefficients.melWeights[m]

                for (j in bins.indices) {
                    acc += weights[j] * power[bins[j]]
                }

                mel[m * FRAMES + frame] = ln(max(acc, 1e-9)).toFloat()
            }
        }

        for (m in 0 until MELS) {
            val base = m * FRAMES
            var mean = 0.0

            for (f in 0 until FRAMES) {
                mean += mel[base + f]
            }

            mean /= FRAMES

            for (f in 0 until FRAMES) {
                mel[base + f] = (mel[base + f] - mean).toFloat()
            }
        }
    }

    private fun fft() {
        var j = 0

        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1

            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }

            j = j xor bit

            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr

                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var length = 2

        while (length <= FFT_SIZE) {
            val angle = -2.0 * Math.PI / length
            val wLenR = kotlin.math.cos(angle)
            val wLenI = kotlin.math.sin(angle)
            var base = 0

            while (base < FFT_SIZE) {
                var wr = 1.0
                var wi = 0.0

                for (k in 0 until length / 2) {
                    val even = base + k
                    val odd = even + length / 2

                    val tr = wr * re[odd] - wi * im[odd]
                    val ti = wr * im[odd] + wi * re[odd]

                    re[odd] = re[even] - tr
                    im[odd] = im[even] - ti
                    re[even] += tr
                    im[even] += ti

                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }

                base += length
            }

            length = length shl 1
        }
    }

    override fun close() {
        session.close()
    }
}
