package com.iraitec

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/**
 * Runs LiteRT-LM in a separate Android process. A native inference crash
 * terminates this service process rather than the main Irai UI process.
 */
class IraiInferenceService : Service() {
    companion object {
        const val REQUEST_GENERATE = 1
        const val REPLY_GENERATE = 2
        const val KEY_PROMPT = "prompt"
        const val KEY_OUTPUT = "output"
        const val KEY_ERROR = "error"
        const val KEY_SUCCESS = "success"
    }

    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    private lateinit var inference: IraiLocalInference

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("IraiIsolatedInference")
        worker.start()
        inference = IraiLocalInference(this)
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(message: Message) {
                if (message.what != REQUEST_GENERATE) return
                val reply = message.replyTo ?: return
                val prompt = message.data.getString(KEY_PROMPT).orEmpty()
                val result = if (prompt.isBlank() || prompt.length > 24_000) {
                    IraiLocalInference.Result(false, "", "Invalid local AI prompt")
                } else {
                    inference.generate(prompt) { }
                }
                try {
                    reply.send(Message.obtain(null, REPLY_GENERATE).apply {
                        data = android.os.Bundle().apply {
                            putBoolean(KEY_SUCCESS, result.completed)
                            putString(KEY_OUTPUT, result.output)
                            putString(KEY_ERROR, result.error)
                        }
                    })
                } catch (_: android.os.RemoteException) {
                    // UI disconnected while inference was running.
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        inference.closeAsync()
        worker.quitSafely()
        super.onDestroy()
    }
}
