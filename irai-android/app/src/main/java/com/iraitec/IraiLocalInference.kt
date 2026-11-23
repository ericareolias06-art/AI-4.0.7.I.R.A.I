package com.iraitec

import android.content.Context
import android.app.ActivityManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

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
    private var initializedModelModified = -1L
    private val preferences = appContext.getSharedPreferences("irai_local_ai", Context.MODE_PRIVATE)
    private val capabilities = IraiDeviceCapabilities(appContext)
    private var loadedOnGpu = false
    private val deviceMemoryGb: Long by lazy {
        val info = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(info)
        info.totalMem / (1024L * 1024L * 1024L)
    }

    /** Serialize model replacement with inference so active native readers finish. */
    fun installModel(staging: File): Boolean {
        if (executor.isShutdown) return false
        val task = try { executor.submit<Boolean> {
            val backup = File(appContext.filesDir, "irai-local.litertlm.backup")
            if (!staging.isFile || staging.length() <= 100_000_000L) return@submit false
            try {
                engine?.close()
                engine = null
                initializedModelSize = -1L
                initializedModelModified = -1L
                loadedOnGpu = false
                backup.delete()
                if (modelFile.exists() && !modelFile.renameTo(backup)) return@submit false
                if (!staging.renameTo(modelFile)) {
                    if (backup.exists()) backup.renameTo(modelFile)
                    return@submit false
                }
                backup.delete()
                true
            } catch (error: Exception) {
                android.util.Log.e("IRAI_LOCAL", "Could not replace AI model", error)
                if (!modelFile.exists() && backup.exists()) backup.renameTo(modelFile)
                false
            }
        } } catch (error: RejectedExecutionException) { return false }
        return try { task.get() } catch (error: Exception) {
            android.util.Log.e("IRAI_LOCAL", "Model installation worker failed", error)
            false
        }
    }

    fun modelInstalled(): Boolean = modelFile.isFile && modelFile.length() > 100_000_000L

    fun generate(prompt: String, onText: (String) -> Unit): Result {
        if (!modelInstalled()) return Result(false, "", "No on-device model installed")
        val preflight = capabilities.checkBeforeInference()
        if (!preflight.supported) return Result(false, "", preflight.reason)
        if (executor.isShutdown) return Result(false, "", "Local AI is shutting down. Please reopen Irai.")
        val future: Future<Result> = try { executor.submit<Result> {
            val output = StringBuilder()
            try {
                if (engine == null || initializedModelSize != modelFile.length() ||
                    initializedModelModified != modelFile.lastModified()) {
                    engine?.close()
                    engine = null
                    loadedOnGpu = false
                    val tryGpu = preferences.getBoolean("gpu_model", false) &&
                        preferences.getBoolean("prefer_gpu", true) &&
                        deviceMemoryGb >= 6L &&
                        android.os.Build.VERSION.SDK_INT >= 31
                    if (tryGpu) {
                        try {
                            val accelerated = Engine(EngineConfig(
                                modelPath = modelFile.absolutePath,
                                backend = Backend.GPU(),
                                cacheDir = appContext.cacheDir.absolutePath
                            ))
                            try {
                                accelerated.initialize()
                                engine = accelerated
                                loadedOnGpu = true
                            } catch (error: Exception) {
                                accelerated.close()
                                android.util.Log.w("IRAI_LOCAL", "GPU unavailable, falling back to CPU", error)
                            }
                        } catch (error: Exception) {
                            android.util.Log.w("IRAI_LOCAL", "Could not create GPU engine", error)
                        }
                    }
                    if (engine == null) {
                        val cpu = Engine(EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = appContext.cacheDir.absolutePath
                        ))
                        try {
                            cpu.initialize()
                            engine = cpu
                        } catch (error: Exception) {
                            cpu.close()
                            throw error
                        }
                    }
                    initializedModelSize = modelFile.length()
                    initializedModelModified = modelFile.lastModified()
                }
                val chat = checkNotNull(engine).createConversation()
                try {
                    runBlocking {
                        chat.sendMessageAsync(prompt).collect { message ->
                            val delta = message.toString()
                            if (delta.isNotEmpty()) {
                                if (output.length + delta.length > 12_000) {
                                    throw IllegalStateException("AI response exceeded the safe output limit")
                                }
                                output.append(delta)
                                try { onText(delta) } catch (callbackError: Exception) {
                                    android.util.Log.e("IRAI_LOCAL", "Streaming callback failed", callbackError)
                                }
                            }
                        }
                    }
                } finally {
                    chat.close()
                }
                Result(output.isNotEmpty(), output.toString())
            } catch (e: Throwable) {
                android.util.Log.e("IRAI_LOCAL", "On-device inference failed", e)
                // A failed native session may leave its engine unusable for the next turn.
                try { engine?.close() } catch (_: Throwable) {}
                engine = null
                initializedModelSize = -1L
                initializedModelModified = -1L
                loadedOnGpu = false
                Result(false, output.toString(), if (e is OutOfMemoryError) "Not enough memory to run the local AI model on this device." else "Local model could not finish")
            }
        } } catch (e: RejectedExecutionException) {
            return Result(false, "", "Local AI is shutting down. Please reopen Irai.")
        }
        return try {
            future.get()
        } catch (e: Exception) {
            android.util.Log.e("IRAI_LOCAL", "Local AI worker failed", e)
            Result(false, "", "Local model worker unavailable")
        }
    }

    fun closeAsync() {
        if (executor.isShutdown) return
        try { executor.execute {
            try {
                engine?.close()
            } catch (error: Throwable) {
                android.util.Log.w("IRAI_LOCAL", "Could not close local AI engine", error)
            } finally {
                engine = null
            }
        } } catch (_: RejectedExecutionException) { }
        executor.shutdown()
    }
}
