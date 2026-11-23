package irai.hybrid

/**
 * Transport-neutral engine. Keep your existing UI, speech recognition, TTS and
 * memory store. Connect their implementations through the interfaces below.
 *
 * Local and cloud adapters MUST deliver tokens in order and call onDone once.
 * If an adapter emits any token, automatic failover is disabled to avoid
 * speaking a duplicated or contradictory answer.
 */
data class IraiMessage(val role: String, val content: String)
data class IraiRequest(
    val messages: List<IraiMessage>,
    val preferLocal: Boolean = true,
    val allowCloud: Boolean = true
)
interface IraiStream {
    fun onToken(text: String)
    fun onDone()
    fun onError(error: Throwable)
}
interface IraiGeneration {
    fun cancel()
}
interface IraiBackend {
    fun isReady(): Boolean
    fun generate(request: IraiRequest, callback: IraiStream): IraiGeneration
}
interface IraiMemory {
    /** Return recent user/assistant messages for this user, in ascending order. */
    fun history(): List<IraiMessage>
    /** Persist a finished turn exactly once; protect this data at rest. */
    fun saveTurn(user: String, assistant: String)
}
interface IraiVoice {
    /** Existing TTS integration; send complete sentences when possible. */
    fun speak(text: String)
}
class IraiHybridEngine(
    private val local: IraiBackend,
    private val cloud: IraiBackend,
    private val memory: IraiMemory,
    private val voice: IraiVoice,
    private val personality: String
) {
    /**
     * Call from UI after speech-to-text or typed input completes.
     * Callbacks may arrive on background threads: dispatch to main thread
     * in the Android UI adapter.
     */
    fun reply(
        input: String,
        preferLocal: Boolean = true,
        allowCloud: Boolean = true,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ): IraiGeneration {
        val prompt = listOf(IraiMessage("system", personality)) +
            memory.history().takeLast(16) +
            IraiMessage("user", input)
        val request = IraiRequest(prompt, preferLocal, allowCloud)
        val guard = Any()
        var stopped = false
        var completed = false
        var current: IraiGeneration? = null
        var seenToken = false
        var switched = false
        val answer = StringBuilder()
        val speech = StringBuilder()

        fun emitSentenceIfReady() {
            val last = speech.lastOrNull()
            if (last == '.' || last == '!' || last == '?' || last == '\n') {
                val line = speech.toString().trim()
                speech.clear()
                if (line.isNotEmpty()) voice.speak(line)
            }
        }
        fun start(backend: IraiBackend) {
            current = backend.generate(request, object : IraiStream {
                override fun onToken(text: String) = synchronized(guard) {
                    if (stopped || completed) return@synchronized
                    if (text.isEmpty()) return@synchronized
                    seenToken = true
                    answer.append(text)
                    speech.append(text)
                    onToken(text)
                    emitSentenceIfReady()
                }
                override fun onDone() = synchronized(guard) {
                    if (stopped || completed) return@synchronized
                    completed = true
                    val last = speech.toString().trim()
                    if (last.isNotEmpty()) voice.speak(last)
                    val final = answer.toString()
                    if (final.isNotBlank()) memory.saveTurn(input, final)
                    onComplete(final)
                }
                override fun onError(error: Throwable) = synchronized(guard) {
                    if (stopped || completed) return@synchronized
                    if (backend === local && !seenToken && !switched &&
                        allowCloud && cloud.isReady()) {
                        switched = true
                        start(cloud)
                    } else {
                        completed = true
                        onError(error)
                    }
                }
            })
        }
        synchronized(guard) {
            when {
                preferLocal && local.isReady() -> start(local)
                allowCloud && cloud.isReady() -> start(cloud)
                local.isReady() -> start(local)
                else -> onError(IllegalStateException("No AI backend available"))
            }
        }
        return object : IraiGeneration {
            override fun cancel() = synchronized(guard) {
                stopped = true
                current?.cancel()
            }
        }
    }
}
