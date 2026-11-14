package com.iraitec

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Optional on-device LiteRT-LM inference. Does not change Irai's existing
 * speech, login, subscription, API, or cloud pipeline.
 *
 * A single worker thread owns the native engine, conversations and teardown.
 * Place a compatible .litertlm model at filesDir/irai-local.litertlm using
 * the explicit user-controlled picker in MainActivity.
 */
class IraiLocalInference(context: Context) {
    data class Result(
        val completed: Boolean,
        val output: String,
        val error: String? = null
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val modelFile = File(appContext.filesDir, "irai-local.litertlm")
    private var engine: Engine? = null
    private var initializedModelSize = -1L

    fun modelInstalled(): Boolean = modelFile.isFile && modelFile.length() > 100_000_000L

    fun generate(prompt: String, onText: (String) -> Unit): Result {
        if (!modelInstalled()) return Result(false, "", "No on-device model installed")
        val future: Future<Result> = executor.submit<Result> {
            val output = StringBuilder()
            try {
                if (engine == null || initializedModelSize != modelFile.length()) {
                    engine?.close()
                    engine = null
                    val candidate = Engine(
                        EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = appContext.cacheDir.absolutePath
                        )
                    )
                    // Don't close a candidate if initialize() fails.
                    candidate.initialize()
                    engine = candidate
                    initializedModelSize = modelFile.length()
                }
                val chat = checkNotNull(engine).createConversation()
                try {
                    runBlocking {
                        chat.sendMessageAsync(prompt).collect { message ->
                            val delta = message.toString()
                            if (delta.isNotEmpty()) {
                                output.append(delta)
                                onText(delta)
                            }
                        }
                    }
                } finally {
                    chat.close()
                }
                Result(output.isNotEmpty(), output.toString())
            } catch (e: Exception) {
                android.util.Log.w("IRAI_LOCAL", "On-device inference unavailable", e)
                Result(false, output.toString(), "Local model could not finish")
            }
        }
        return try {
            future.get()
        } catch (e: Exception) {
            Result(false, "", "Local model worker unavailable")
        }
    }

    fun closeAsync() {
        executor.execute {
            try {
                engine?.close()
            } catch (_: Exception) {
            } finally {
                engine = null
            }
        }
        executor.shutdown()
    }
}
