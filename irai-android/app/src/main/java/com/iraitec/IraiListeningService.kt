package com.iraitec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class IraiListeningService : Service() {
    companion object {
        private const val TAG = "IraiListening"
        private const val CHANNEL_ID = "irai_listening"
        private const val NOTIFICATION_ID = 1001
    }

    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var engine: WakeWordEngine? = null
    @Volatile private var detected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing wake-word detector…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.get()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                stopSelf()
                return START_NOT_STICKY
            }
            running.set(true)
            Thread({ listenLoop() }, "IraiAudioLoop").start()
        }
        return START_STICKY
    }

    private fun listenLoop() {
        var localEngine: WakeWordEngine? = null
        var localRecorder: AudioRecord? = null
        try {
            localEngine = WakeWordEngine(this)
            engine = localEngine

            val minBuffer = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) error("AudioRecord does not support 16 kHz mono input")

            localRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 6400)
            )
            if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
                error("Microphone initialization failed")
            }
            recorder = localRecorder
            localRecorder.startRecording()
            updateNotification("Listening for “Irai”")

            val shorts = ShortArray(1600)
            val floats = FloatArray(shorts.size)

            while (running.get()) {
                val count = localRecorder.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (count < 0) Log.e(TAG, "AudioRecord read error: $count")
                    continue
                }
                for (i in 0 until count) floats[i] = shorts[i] / 32768.0f

                if (localEngine.accept(floats.copyOf(count))) {
                    detected = true
                    updateNotification("Irai heard you!")
                    Log.i(TAG, "Wake word detected")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Wake-word listening stopped", t)
            updateNotification("Listening error — reopen Irai Tec")
        } finally {
            running.set(false)
            try { localRecorder?.stop() } catch (_: Exception) {}
            localRecorder?.release()
            recorder = null
            try { localEngine?.close() } catch (_: Exception) {}
            engine = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Irai listening",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Irai Tec")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
