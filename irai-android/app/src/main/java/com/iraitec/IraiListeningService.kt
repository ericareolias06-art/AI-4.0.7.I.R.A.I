
package com.iraitec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class IraiListeningService : Service() {

    companion object {
        private const val TAG = "IraiListening"
        private const val CHANNEL_ID = "irai_listening"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_DIAGNOSTIC = "com.iraitec.WAKE_DIAGNOSTIC"
        const val EXTRA_STATUS = "status"
        const val ACTION_RESUME_LISTENING = "com.iraitec.RESUME_LISTENING"

        // Keep an existing foreground microphone service alive between turns.
        // Resuming its receiver avoids Android's background service-start limits.
        @Volatile var serviceAlive: Boolean = false
            private set

        // Activity watchdog uses this to notice an idle/stopped detector.
        @Volatile var wakeListenerRunning: Boolean = false
            private set
    }

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var waitingForCommand = false
    private var retryCount = 0
    private val retryRunnable = Runnable { startListeningIfNeeded() }

    private fun isCallUsingMicrophone(): Boolean {
        val mode = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun startListeningIfNeeded() {
        if (destroyed || waitingForCommand || running.get()) return
        // Ordinary applications are muted during phone/VoIP calls on Android.
        // Avoid consuming a silent audio stream, then retry after the call.
        if (isCallUsingMicrophone()) {
            updateNotification("Paused during call; will resume afterward")
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, 3_000L)
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            reportStatus("ERROR: Microphone permission is not granted")
            updateNotification("Microphone permission required")
            return
        }
        if (running.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(retryRunnable)
            Thread({ listenLoop() }, "IraiAudioLoop").start()
        }
    }

    private fun retryListening() {
        if (destroyed || waitingForCommand) return
        if (isCallUsingMicrophone()) {
            updateNotification("Paused during call; will resume afterward")
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, 3_000L)
            return
        }
        val delayMs = minOf(30_000L, 2_000L shl minOf(retryCount++, 4))
        updateNotification("Reconnecting microphone…")
        reportStatus("Microphone interrupted; retrying wake-word listening")
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delayMs)
    }


    private val resumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RESUME_LISTENING -> {
                    waitingForCommand = false
                    retryCount = 0
                    startListeningIfNeeded()
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (!waitingForCommand) startListeningIfNeeded()
                }
            }
        }
    }

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var engine: WakeWordEngine? = null

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        createNotificationChannel()

        val resumeFilter = IntentFilter(ACTION_RESUME_LISTENING).apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resumeReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resumeReceiver, resumeFilter)
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Preparing wake-word detector…")
        )
        reportStatus("Service started; preparing detector")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        waitingForCommand = false
        startListeningIfNeeded()
        return START_STICKY
    }

    private fun listenLoop() {
        var localEngine: WakeWordEngine? = null
        var localRecorder: AudioRecord? = null
        var detectedWakeWord = false

        try {
            reportStatus("Loading wake-word model")
            localEngine = WakeWordEngine(this) { diagnostic ->
            reportStatus(diagnostic)
            }
            engine = localEngine

            reportStatus("Model loaded; preparing microphone")

            val minBuffer = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) {
                error("AudioRecord does not support 16 kHz mono input")
            }

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
            wakeListenerRunning = true

            updateNotification("Listening for “Irai”")
            reportStatus("LISTENING: Microphone recording at 16 kHz")
            retryCount = 0

            val shorts = ShortArray(1600)
            val floats = FloatArray(shorts.size)
            var totalSamples = 0L

            while (running.get()) {
                if (isCallUsingMicrophone()) {
                    reportStatus("CALL_ACTIVE: Pausing wake-word detection until call ends")
                    break
                }
                val count = localRecorder.read(
                    shorts,
                    0,
                    shorts.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count <= 0) {
                    if (count < 0) {
                        val message = "Microphone read error: $count"
                        Log.e(TAG, message)
                        reportStatus("ERROR: $message")
                        break
                    }
                    continue
                }

                totalSamples += count

                for (i in 0 until count) {
                    floats[i] = shorts[i] / 32768.0f
                }

                // Report that audio is flowing, approximately every 5 seconds.
                if (totalSamples % 80000 < count) {
                    reportStatus("LISTENING: Microphone audio is arriving")
                }

                if (localEngine.accept(floats.copyOf(count))) {
                    val message = "DETECTED: Irai heard you!"
                    Log.i(TAG, message)
                    updateNotification("Irai heard you!")
                    detectedWakeWord = true
                    waitingForCommand = true
                    reportStatus(message)
                    break
                }
            }
        } catch (t: Throwable) {
            val message = "ERROR: ${t.javaClass.simpleName}: ${t.message ?: "Unknown error"}"
            Log.e(TAG, "Wake-word listening stopped", t)
            updateNotification("Listening error — reopen Irai Tec")
            reportStatus(message)
        } finally {
            wakeListenerRunning = false
            running.set(false)

            try {
                localRecorder?.stop()
            } catch (_: Exception) {
            }

            localRecorder?.release()
            recorder = null

            try {
                localEngine?.close()
            } catch (_: Exception) {
            }

            engine = null

            if (detectedWakeWord) {
                updateNotification("Waiting for your command…")
                reportStatus("READY_FOR_COMMAND")
            } else if (!destroyed) {
                retryListening()
            }
        }
    }

    private fun reportStatus(message: String) {
        Log.d(TAG, message)

        val intent = Intent(ACTION_DIAGNOSTIC).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
        }

        sendBroadcast(intent)
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
        destroyed = true
        serviceAlive = false
        wakeListenerRunning = false
        mainHandler.removeCallbacks(retryRunnable)
        running.set(false)

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(resumeReceiver)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
