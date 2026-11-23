package com.iraitec

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous bridge for callers already running on a background thread.
 * Native inference happens in :irai_ai, not the application's UI process.
 */
class IraiIsolatedInference(private val context: Context) {
    fun generate(prompt: String, onText: (String) -> Unit): IraiLocalInference.Result {
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var result = IraiLocalInference.Result(false, "", "Local AI process stopped unexpectedly")
        val replies = HandlerThread("IraiAiReply").apply { start() }
        val replyMessenger = Messenger(object : Handler(replies.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != IraiInferenceService.REPLY_GENERATE) return
                val data = msg.data
                result = IraiLocalInference.Result(
                    data.getBoolean(IraiInferenceService.KEY_SUCCESS),
                    data.getString(IraiInferenceService.KEY_OUTPUT).orEmpty(),
                    data.getString(IraiInferenceService.KEY_ERROR)
                )
                if (finished.compareAndSet(false, true)) latch.countDown()
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    Messenger(binder).send(Message.obtain(null, IraiInferenceService.REQUEST_GENERATE).apply {
                        replyTo = replyMessenger
                        data = android.os.Bundle().apply {
                            putString(IraiInferenceService.KEY_PROMPT, prompt)
                        }
                    })
                } catch (_: Exception) {
                    result = IraiLocalInference.Result(false, "", "Could not contact local AI process")
                    if (finished.compareAndSet(false, true)) latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                result = IraiLocalInference.Result(false, "", "Local AI crashed. Irai is still running; please retry.")
                if (finished.compareAndSet(false, true)) latch.countDown()
            }
            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
        }
        var bound = false
        try {
            bound = app.bindService(
                Intent(app, IraiInferenceService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) return IraiLocalInference.Result(false, "", "Could not start local AI process")
            if (!latch.await(120, TimeUnit.SECONDS)) {
                return IraiLocalInference.Result(false, "", "Local AI timed out. Please retry.")
            }
            if (result.completed && result.output.isNotEmpty()) onText(result.output)
            return result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return IraiLocalInference.Result(false, "", "Local AI request interrupted")
        } catch (_: Exception) {
            return IraiLocalInference.Result(false, "", "Local AI service unavailable")
        } finally {
            if (bound) try { app.unbindService(connection) } catch (_: Exception) {}
            replies.quitSafely()
        }
    }
}
