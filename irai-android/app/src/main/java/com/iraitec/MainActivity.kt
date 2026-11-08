
package com.iraitec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val api =
        "https://ai-4-0-7-i-r-a-i-1.onrender.com"

    private val bg = Color.rgb(18, 18, 32)
    private val accent = Color.rgb(150, 120, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(190, 190, 210)

    private lateinit var root: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmInput: EditText
    private lateinit var message: TextView
    private lateinit var diagnosticText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var registering = false
    private var currentEmail = ""
    private var receiverRegistered = false
    private var loggedIn = false
    private var awaitingCommand = false
    private var listeningForCommand = false
    private var notesMode = false
    private var currentNoteId: Long? = null
    private var currentNoteTitle = ""
    private var currentNoteBody = ""
    private var noteHasChanges = false
    private var pendingDeleteNoteId: Long? = null
    private val notesDb by lazy { IraiNotesDatabase(this) }
    private var latestDiagnostic = "Waiting for listening service..."

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechTts: TextToSpeech? = null
    private var ttsReady = false

    private val restartListeningRunnable = Runnable {
        if (loggedIn && !awaitingCommand && !listeningForCommand) {
            if (notesMode) {
                startVoiceCommand()
            } else {
                sendBroadcast(
                    Intent(IraiListeningService.ACTION_RESUME_LISTENING)
                        .setPackage(packageName)
                )
            }
        }
    }

    private val diagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != IraiListeningService.ACTION_DIAGNOSTIC) {
                return
            }

            val status = intent.getStringExtra(
                IraiListeningService.EXTRA_STATUS
            ) ?: return

            runOnUiThread {
                latestDiagnostic = status
                refreshDiagnostic()

                if (status == "READY_FOR_COMMAND" &&
                    loggedIn &&
                    !awaitingCommand
                ) {
                    startVoiceCommand()
                }
            }
        }
    }

    private val sessionPrefs by lazy {
        getSharedPreferences("irai_session", Context.MODE_PRIVATE)
    }

    private fun openApiConnection(path: String): HttpURLConnection {
        val connection =
            URL(api + path).openConnection() as HttpURLConnection
        sessionPrefs.getString("cookie", null)?.let {
            connection.setRequestProperty("Cookie", it)
        }
        return connection
    }

    private fun saveSessionCookie(connection: HttpURLConnection) {
        val cookies = connection.headerFields
            .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values.flatten()

        val sessionCookie = cookies.firstOrNull {
            it.substringBefore("=").trim() == "session"
        }?.substringBefore(";")

        if (sessionCookie != null) {
            sessionPrefs.edit().putString("cookie", sessionCookie).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieHandler.setDefault(
            CookieManager(null, CookiePolicy.ACCEPT_ALL)
        )

        window.statusBarColor = bg
        window.navigationBarColor = bg

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 24)
            setBackgroundColor(bg)
        }

        setContentView(root)

        val filter = IntentFilter(
            IraiListeningService.ACTION_DIAGNOSTIC
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                diagnosticReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(diagnosticReceiver, filter)
        }

        receiverRegistered = true

        speechTts = TextToSpeech(this) { status ->
            runOnUiThread {
                ttsReady = status == TextToSpeech.SUCCESS

                if (ttsReady) {
                    speechTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "irai_response") runOnUiThread {
                                if (notesMode && loggedIn && !awaitingCommand) startVoiceCommand()
                            }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == "irai_response") runOnUiThread {
                                if (notesMode && loggedIn && !awaitingCommand) handler.postDelayed({ startVoiceCommand() }, 800)
                            }
                        }
                    })
                    val languageResult = speechTts?.setLanguage(Locale.US)

                    speechTts?.setSpeechRate(0.92f)
                    speechTts?.setPitch(1.18f)
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        latestDiagnostic =
                            "Text-to-speech language unavailable."
                        refreshDiagnostic()
                    }
                }
            }
        }

        restoreSession()
    }

    private fun restoreSession() {
        val cookie = sessionPrefs.getString("cookie", null)
        if (cookie.isNullOrBlank()) {
            showAuth()
            return
        }

        thread {
            var email = ""
            var valid = false
            var rejected = false
            var connectionFailed = false

            try {
                val connection = openApiConnection("/api/me")
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val code = connection.responseCode

                if (code == 401 || code == 403) {
                    rejected = true
                } else if (code in 200..299) {
                    val result = connection.inputStream
                        .bufferedReader().use { it.readText() }
                    val json = JSONObject(result)
                    email = json.optString("email", "")
                    valid = email.isNotBlank()
                } else {
                    connectionFailed = true
                }

                connection.disconnect()
            } catch (_: Exception) {
                connectionFailed = true
            }

            runOnUiThread {
                when {
                    valid -> {
                        currentEmail = email
                        loggedIn = true
                        showHome(email)
                        requestMicrophonePermission()
                    }
                    rejected -> {
                        sessionPrefs.edit().remove("cookie").apply()
                        loggedIn = false
                        showAuth()
                        message.text = "Your session expired. Please log in again."
                    }
                    connectionFailed -> {
                        showAuth()
                        message.text = "Unable to verify your session. Check your internet and try again. Your saved session has been kept."
                    }
                    else -> {
                        showAuth()
                        message.text = "Unable to verify your session. Please try again."
                    }
                }
            }
        }
    }

    private fun showAuth() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 24)
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        content.addView(label("✦", 58, accent))
        content.addView(label("IRAI TEC", 30, white))
        content.addView(label("Your AI companion", 16, muted))
        content.addView(
            label("Sign in to your Irai Tec account", 18, white)
                .apply { setPadding(0, 28, 0, 20) }
        )

        emailInput = field("Email address").apply {
            inputType = 33
        }
        content.addView(emailInput)

        passwordInput = field("Password").apply {
            inputType = 129
        }
        content.addView(passwordInput)

        confirmInput = field("Confirm password").apply {
            inputType = 129
            visibility = View.GONE
        }
        content.addView(confirmInput)

        val action = Button(this).apply {
            text = "Log In"
            setTextColor(white)
            setBackgroundColor(accent)
            setOnClickListener { submitAuth() }
        }
        content.addView(action, params())

        val toggle = TextView(this).apply {
            text = "Don't have an account? Register"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(8, 22, 8, 22)

            setOnClickListener {
                registering = !registering
                action.text =
                    if (registering) "Create Account" else "Log In"

                confirmInput.visibility =
                    if (registering) View.VISIBLE else View.GONE

                message.text = ""
                text = if (registering) {
                    "Already have an account? Log in"
                } else {
                    "Don't have an account? Register"
                }
            }
        }
        content.addView(toggle)
        content.addView(TextView(this).apply {
            text = "Privacy Policy"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(8, 14, 8, 14)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iraitec.dev/privacy.html")))
            }
        })

        message = label("", 14, muted)
        content.addView(message)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun submitAuth() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirm = confirmInput.text.toString()

        if (!email.contains("@") || password.isBlank()) {
            message.text = "Enter a valid email and password."
            return
        }

        if (registering && password.length < 12) {
            message.text = "Password must be at least 12 characters."
            return
        }

        if (registering && password != confirm) {
            message.text = "Passwords do not match."
            return
        }

        message.text = "Connecting securely..."

        val endpoint = if (registering) {
            "/api/register"
        } else {
            "/api/login"
        }

        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            if (registering) put("confirmPassword", confirm)
        }

        thread {
            var result = ""
            var success = false

            try {
                val connection =
                    openApiConnection(endpoint)

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                saveSessionCookie(connection)
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                result = stream?.bufferedReader()?.use { it.readText() } ?: ""
                success = code in 200..299
                connection.disconnect()
            } catch (_: Exception) {
                result = "Could not connect. Check your internet and try again."
            }

            runOnUiThread {
                val json = try {
                    JSONObject(result)
                } catch (_: Exception) {
                    JSONObject()
                }

                message.text = json.optString(
                    "message",
                    json.optString("error", result)
                )

                if (success && registering) {
                    registering = false
                    confirmInput.visibility = View.GONE
                    passwordInput.text.clear()
                    message.text = "Account created! You can now log in."
                } else if (success) {
                    currentEmail = email
                    loggedIn = true
                    showHome(email)
                    requestMicrophonePermission()
                }
            }
        }
    }

    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
            )
        } else {
            requestMicrophonePermissionAfterNotifications()
        }
    }

    private fun requestMicrophonePermissionAfterNotifications() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO), 1001
            )
        } else {
            startIraiListeningService()
        }
    }

    private fun startIraiListeningService() {
        if (!loggedIn || awaitingCommand || listeningForCommand || notesMode) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            latestDiagnostic = "Microphone permission has not been granted."
            refreshDiagnostic()
            return
        }

        try {
            val serviceIntent =
                Intent(this, IraiListeningService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            latestDiagnostic = "Starting listening service..."
            refreshDiagnostic()
        } catch (e: Exception) {
            latestDiagnostic = "Could not start listening: ${e.message}"
            refreshDiagnostic()
        }
    }

    private fun stopIraiListeningService() {
        try {
            stopService(Intent(this, IraiListeningService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            1002 -> requestMicrophonePermissionAfterNotifications()
            1001 -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startIraiListeningService()
                } else {
                    latestDiagnostic = "Microphone permission denied."
                    refreshDiagnostic()
                }
            }
        }
    }

    // In-app speech recognition: does not launch the Google voice UI.
    private fun startVoiceCommand() {
        if (awaitingCommand || !loggedIn) return

        awaitingCommand = true
        listeningForCommand = true
        handler.removeCallbacks(restartListeningRunnable)


        latestDiagnostic = "Irai heard you. Listening for your command..."
        refreshDiagnostic()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            awaitingCommand = false
            listeningForCommand = false
            speakResponse("Speech recognition is not available on this device.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        runOnUiThread {
                            latestDiagnostic = "Listening inside Irai Tec..."
                            refreshDiagnostic()
                        }
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        runOnUiThread {
                            latestDiagnostic = "Processing your command..."
                            refreshDiagnostic()
                        }
                    }

                    override fun onError(error: Int) {
                        runOnUiThread {
                            awaitingCommand = false
                            listeningForCommand = false
                            if (notesMode) {
                                latestDiagnostic = "Notes mode is still open. Listening again..."
                                refreshDiagnostic()
                                handler.postDelayed({ startVoiceCommand() }, 1200)
                            } else {
                                latestDiagnostic = "Speech recognition error ($error). Try again."
                                refreshDiagnostic()
                                restartListeningAfterDelay()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val spokenText = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            .orEmpty()

                        runOnUiThread {
                            awaitingCommand = false
                            listeningForCommand = false

                            if (spokenText.isBlank()) {
                                latestDiagnostic = "I didn't catch that."
                                refreshDiagnostic()
                                speakResponse("Sorry, I didn't catch that.")
                            } else {
                                latestDiagnostic = "You said: $spokenText"
                                refreshDiagnostic()
                                handleVoiceCommand(spokenText)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
            )

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.US.toLanguageTag()
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            awaitingCommand = false
            listeningForCommand = false
            latestDiagnostic = "Could not start speech recognition: ${e.message}"
            refreshDiagnostic()
            restartListeningAfterDelay()
        }
    }

    private fun handleVoiceCommand(command: String) {
        // Speech recognition may include the wake word in the recognized phrase.
        // Remove it before routing so Notes commands never fall through to Ollama.
        val routedCommand = command.trim()
            .replace(
                Regex("(?i)^(?:hey\\s+)?(?:irai|i\\s*ray)[,\\s:.-]+"),
                ""
            )
            .trim()
        val text = routedCommand.lowercase(Locale.US)

        if (notesMode) {
            handleNotesCommand(routedCommand)
            return
        }

        when {
            text == "open notes" ||
                text == "open my notes" ||
                text == "open the notes" ||
                text == "open notepad" ||
                text == "notepad" ||
                text == "take a note" ||
                text == "take notes" ||
                text.startsWith("open notes ") -> startNotesMode()

            text.startsWith("open note ") ||
                text.startsWith("reopen note ") ->
                openSavedNote(routedCommand.substringAfter("note ").trim())

            text == "read my notes" || text == "list my notes" ->
                speakSavedNoteList()

            text.startsWith("read note ") ->
                readSavedNote(routedCommand.substringAfter("read note ").trim())

            text.startsWith("delete note ") ->
                requestNoteDeletion(routedCommand.substringAfter("delete note ").trim())

            "time" in text -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                speakResponse("The time is $time.")
            }

            "date" in text || "today" in text -> {
                val date = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
                speakResponse("Today is $date.")
            }

            "hello" in text || text == "hi" ->
                speakResponse("Hello! I'm Irai. How can I help?")

            "your name" in text ->
                speakResponse("My name is Irai. I'm ready to help.")

            "open youtube" in text -> {
                speakResponse("Opening YouTube.")
                openWebPage("https://www.youtube.com")
            }

            "open google" in text -> {
                speakResponse("Opening Google.")
                openWebPage("https://www.google.com")
            }

            "stop listening" in text || "go to sleep" in text -> {
                stopIraiListeningService()
                speakResponse("Okay. Irai is going to sleep.")
            }

            else -> sendMessageToIrai(routedCommand)
        }
    }

    private fun sendMessageToIrai(message: String) {
    latestDiagnostic = "Irai is thinking..."
    refreshDiagnostic()

    thread {
        var reply = ""

        try {
            val connection = openApiConnection("/api/ai/chat")
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 100000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            val payload = JSONObject()
                .put("message", message)
                .toString()

            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }

            saveSessionCookie(connection)

            val status = connection.responseCode
            val responseStream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            val json = JSONObject(responseText)

            reply = if (status in 200..299) {
                json.optString("response", "I received an empty response.")
            } else {
                json.optString(
                    "message",
                    "Sorry, I couldn't reach my AI service."
                )
            }

            connection.disconnect()

        } catch (e: Exception) {
            reply = "I couldn't connect to my AI service. Please try again."
            android.util.Log.e(
                "IRAI_AI",
                "AI request failed",
                e
            )
        }

        runOnUiThread {
            speakResponse(reply)
        }
    }
    }
    private fun startNotesMode() {
        notesMode = true
        currentNoteId = null
        currentNoteTitle = "Untitled note"
        currentNoteBody = ""
        noteHasChanges = false
        pendingDeleteNoteId = null
        handler.removeCallbacks(restartListeningRunnable)
        stopIraiListeningService()
        latestDiagnostic = "Notes mode active. Waiting for a note."
        refreshDiagnostic()
        speakResponse("Notes is open. Tell me what to name your note, or start dictating.")
    }

    private fun handleNotesCommand(command: String) {
        val spoken = command.trim()
        val lower = spoken.lowercase(Locale.US)
        when {
            lower == "save and quit" || lower == "save and close" -> {
                if (currentNoteBody.isNotBlank()) saveCurrentNote()
                quitNotesMode()
            }
            lower == "save note" || lower == "save" -> {
                if (saveCurrentNote()) speakResponse("Your note is saved.")
                else speakResponse("There is no note text to save yet. Start dictating first.")
            }
            lower == "quit notes" || lower == "close notes" || lower == "exit notes" -> {
                if (noteHasChanges && currentNoteBody.isNotBlank()) speakResponse("Would you like me to save this note before I quit? Say save and quit, or discard and quit.")
                else quitNotesMode()
            }
            lower == "discard and quit" || lower == "discard note" -> quitNotesMode()
            lower == "new note" -> {
                if (noteHasChanges && currentNoteBody.isNotBlank()) {
                    speakResponse("Save this note before starting another one, or say discard and quit.")
                } else {
                    currentNoteId = null
                    currentNoteTitle = "Untitled note"
                    currentNoteBody = ""
                    noteHasChanges = false
                    speakResponse("A new note is ready. What should I call it?")
                }
            }
            lower.startsWith("name this note ") || lower.startsWith("rename note ") -> {
                val newTitle = if (lower.startsWith("name this note ")) spoken.substringAfter("name this note ").trim() else spoken.substringAfter("rename note ").trim()
                if (newTitle.isBlank()) speakResponse("What would you like to name this note?")
                else {
                    currentNoteTitle = newTitle
                    noteHasChanges = true
                    speakResponse("I'll call this note $newTitle.")
                }
            }
            lower == "read this note" || lower == "read my note" -> {
                speakResponse(if (currentNoteBody.isBlank()) "This note is empty." else "$currentNoteTitle. $currentNoteBody")
            }
            lower.startsWith("open note ") || lower.startsWith("reopen note ") -> {
                if (noteHasChanges && currentNoteBody.isNotBlank()) speakResponse("Save this note before opening another one, or say discard and quit.")
                else openSavedNote(spoken.substringAfter("note ").trim())
            }
            lower == "delete this note" || lower.startsWith("delete note ") -> {
                val title = if (lower == "delete this note") currentNoteTitle else spoken.substringAfter("delete note ").trim()
                requestNoteDeletion(title)
            }
            lower == "yes delete it" || lower == "confirm delete" -> {
                val id = pendingDeleteNoteId
                if (id == null) speakResponse("There is no note waiting to be deleted.")
                else {
                    notesDb.deleteNote(id)
                    pendingDeleteNoteId = null
                    if (currentNoteId == id) {
                        currentNoteId = null
                        currentNoteBody = ""
                        currentNoteTitle = "Untitled note"
                        noteHasChanges = false
                    }
                    speakResponse("The note has been deleted.")
                }
            }
            lower == "no keep it" || lower == "cancel delete" -> {
                pendingDeleteNoteId = null
                speakResponse("Okay, I'll keep the note.")
            }
            lower == "read my notes" || lower == "list my notes" -> speakSavedNoteList()
            else -> {
                currentNoteBody = if (currentNoteBody.isBlank()) spoken else currentNoteBody.trimEnd() + "\n" + spoken
                noteHasChanges = true
                speakResponse("Added to $currentNoteTitle.")
            }
        }
    }

    private fun saveCurrentNote(): Boolean {
        if (currentNoteBody.isBlank()) return false
        currentNoteId = notesDb.saveNote(currentNoteId, currentNoteTitle, currentNoteBody)
        noteHasChanges = false
        latestDiagnostic = "Saved note: $currentNoteTitle"
        refreshDiagnostic()
        return true
    }

    private fun quitNotesMode() {
        notesMode = false
        currentNoteId = null
        currentNoteTitle = ""
        currentNoteBody = ""
        noteHasChanges = false
        pendingDeleteNoteId = null
        speakResponse("Okay, I'm closing Notes.")
    }

    private fun openSavedNote(title: String) {
        val note = notesDb.findNote(title)
        if (note == null) {
            speakResponse("I couldn't find a note named $title.")
            return
        }
        notesMode = true
        currentNoteId = note.id
        currentNoteTitle = note.title
        currentNoteBody = note.content
        noteHasChanges = false
        handler.removeCallbacks(restartListeningRunnable)
        stopIraiListeningService()
        speakResponse("I've opened ${note.title}. You can add to it, ask me to read it, or say save and quit.")
    }

    private fun speakSavedNoteList() {
        val notes = notesDb.getNotes()
        speakResponse(if (notes.isEmpty()) "You do not have any saved notes yet." else "Your saved notes are: " + notes.joinToString(", ") { it.title })
    }

    private fun readSavedNote(title: String) {
        val note = notesDb.findNote(title)
        speakResponse(if (note == null) "I couldn't find a note named $title." else "${note.title}. ${note.content}")
    }

    private fun requestNoteDeletion(title: String) {
        val note = notesDb.findNote(title)
        if (note == null) speakResponse("I couldn't find a note named $title.")
        else {
            notesMode = true
            handler.removeCallbacks(restartListeningRunnable)
            stopIraiListeningService()
            pendingDeleteNoteId = note.id
            speakResponse("Are you sure you want to delete ${note.title}? Say yes delete it, or cancel delete.")
        }
    }

    private fun openWebPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            speakResponse("I couldn't open that page.")
        }
    }

    private fun speakResponse(text: String) {
        latestDiagnostic = "Irai: $text"
        refreshDiagnostic()

        if (ttsReady) {
            speechTts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "irai_response"
            )
        } else {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }

        if (!notesMode || !ttsReady) restartListeningAfterDelay()
    }

    private fun restartListeningAfterDelay() {
        handler.removeCallbacks(restartListeningRunnable)
        handler.postDelayed(restartListeningRunnable, 1800)
    }

    private fun showHome(email: String) {
        thread {
            var premium = false
            var statusMessage = ""

            try {
                val connection =
                    openApiConnection("/api/me")

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val result = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = JSONObject(result)

                if (code in 200..299) {
                    premium = json.optBoolean("premium", false)
                } else {
                    statusMessage = "Could not verify Premium status."
                }

                connection.disconnect()
            } catch (_: Exception) {
                statusMessage =
                    "Could not verify Premium status. Check your connection."
            }

            runOnUiThread {
                root.removeAllViews()
                root.orientation = LinearLayout.VERTICAL
                root.gravity = Gravity.CENTER
                root.setPadding(24, 20, 24, 20)

                val scroll = ScrollView(this)
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }

                content.addView(label("✦", 48, accent))
                content.addView(label("Welcome to IRAI TEC", 26, white))
                content.addView(label("Signed in as $email", 15, muted))

                if (statusMessage.isNotBlank()) {
                    content.addView(label(statusMessage, 15, muted))
                } else if (premium) {
                    content.addView(label("✓ Premium Active", 22, accent))
                } else {
                    content.addView(label("Unlock IRAI TEC Premium", 20, white))
                    content.addView(label("$3/month", 18, accent))

                    content.addView(Button(this).apply {
                        text = "Subscribe Monthly"
                        setOnClickListener { startCheckout("monthly") }
                    }, params())

                    content.addView(label("$30/year", 18, accent))

                    content.addView(Button(this).apply {
                        text = "Subscribe Yearly"
                        setOnClickListener { startCheckout("yearly") }
                    }, params())

                    content.addView(Button(this).apply {
                        text = "Redeem Promo Code"
                        setOnClickListener { showPromoInput() }
                    }, params())
                }

                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.rgb(30, 30, 48))
                }

                panel.addView(label("WAKE-WORD DIAGNOSTICS", 16, accent))

                diagnosticText = TextView(this).apply {
                    textSize = 14f
                    setTextColor(white)
                    gravity = Gravity.CENTER
                    setPadding(8, 8, 8, 8)
                    text = latestDiagnostic
                }
                panel.addView(diagnosticText)
                content.addView(panel, params())

                content.addView(Button(this).apply {
                    text = "Open Notes"
                    setOnClickListener { startNotesMode() }
                }, params())

                content.addView(Button(this).apply {
                    text = "Test Voice Response"
                    setOnClickListener {
                        speakResponse(
                            "Hello! I'm Irai. My voice response is working."
                        )
                    }
                }, params())

                content.addView(Button(this).apply {
                    text = "Restart Listening"
                    setOnClickListener {
                        if (loggedIn) {
                            startIraiListeningService()
                        }
                    }
                }, params())

                content.addView(Button(this).apply {
                    text = "Privacy Policy"
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iraitec.dev/privacy.html")))
                    }
                }, params())

                content.addView(Button(this).apply {
                    text = "Delete Account"
                    setOnClickListener { confirmAccountDeletion() }
                }, params())

                content.addView(Button(this).apply {
                    text = "Log Out"
                    setOnClickListener { logout() }
                }, params())

                scroll.addView(content)
                root.addView(scroll)
                refreshDiagnostic()
            }
        }
    }

    private fun refreshDiagnostic() {
        if (::diagnosticText.isInitialized) {
            diagnosticText.text = "WAKE-WORD DIAGNOSTICS\n$latestDiagnostic"
        }
    }

    private fun startCheckout(plan: String) {
        Toast.makeText(
            this, "Preparing secure checkout...", Toast.LENGTH_SHORT
        ).show()

        thread {
            var checkoutUrl = ""
            var errorMessage = ""

            try {
                val body = JSONObject().put("plan", plan)
                val connection =
                    openApiConnection("/api/create-checkout-session")

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val result = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try {
                    JSONObject(result)
                } catch (_: Exception) {
                    JSONObject()
                }

                if (code in 200..299) {
                    checkoutUrl = json.optString("url")
                } else {
                    errorMessage = json.optString(
                        "error",
                        json.optString("message", "Could not start checkout.")
                    )
                }

                connection.disconnect()
            } catch (_: Exception) {
                errorMessage = "Could not connect to checkout. Please try again."
            }

            runOnUiThread {
                if (checkoutUrl.startsWith("https://")) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl))
                    )
                } else {
                    Toast.makeText(
                        this,
                        errorMessage.ifBlank {
                            "Checkout link was not returned."
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showPromoInput() {
        val input = EditText(this).apply {
            hint = "Enter promo code"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Redeem Premium Code")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Redeem") { _, _ ->
                redeemPromo(input.text.toString().trim())
            }
            .show()
    }

    private fun redeemPromo(code: String) {
        if (code.isBlank()) {
            Toast.makeText(this, "Enter a promo code.", Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            var responseMessage = "Could not redeem promo code."
            var success = false

            try {
                val body = JSONObject().put("code", code)
                val connection =
                    openApiConnection("/api/redeem-promo")

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val result = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try {
                    JSONObject(result)
                } catch (_: Exception) {
                    JSONObject()
                }

                success = status in 200..299
                responseMessage = json.optString(
                    "message",
                    json.optString(
                        "error",
                        if (success) "Promo code redeemed."
                        else "Could not redeem promo code."
                    )
                )

                connection.disconnect()
            } catch (_: Exception) {
                responseMessage = "Could not connect. Please try again."
            }

            runOnUiThread {
                Toast.makeText(
                    this, responseMessage, Toast.LENGTH_LONG
                ).show()

                if (success) showHome(currentEmail)
            }
        }
    }

    private fun confirmAccountDeletion() {
        AlertDialog.Builder(this)
            .setTitle("Delete Irai Tec account?")
            .setMessage(
                "This permanently deletes your account and associated Irai Tec account data. " +
                "If you have an active Premium subscription, Irai will attempt to cancel it first. " +
                "This action cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete Account") { _, _ ->
                thread {
                    var responseMessage = "Could not delete your account. Please try again."
                    var success = false
                    try {
                        val connection = openApiConnection("/api/delete-account")
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 20000
                        connection.readTimeout = 20000
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use {
                            it.write("{}".toByteArray(Charsets.UTF_8))
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                        val json = JSONObject(body)
                        responseMessage = json.optString("message", responseMessage)
                        success = code in 200..299
                        connection.disconnect()
                    } catch (_: Exception) {
                    }
                    runOnUiThread {
                        Toast.makeText(this, responseMessage, Toast.LENGTH_LONG).show()
                        if (success) {
                            loggedIn = false
                            awaitingCommand = false
                            listeningForCommand = false
                            handler.removeCallbacks(restartListeningRunnable)
                            stopIraiListeningService()
                            sessionPrefs.edit().clear().apply()
                            currentEmail = ""
                            showAuth()
                        }
                    }
                }
            }
            .show()
    }

    private fun logout() {
        val sessionCookie = sessionPrefs.getString("cookie", null)

        loggedIn = false
        awaitingCommand = false
        listeningForCommand = false

        handler.removeCallbacks(restartListeningRunnable)
        stopIraiListeningService()

        // Clear the local session and show login immediately.
        sessionPrefs.edit().remove("cookie").apply()
        currentEmail = ""
        latestDiagnostic = "Signed out."
        showAuth()

        // Notify the server in the background without delaying logout.
        thread {
            try {
                val connection =
                    URL(api + "/api/logout").openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    sessionCookie?.let {
                        connection.setRequestProperty("Cookie", it)
                    }

                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun label(
        textValue: String,
        size: Int,
        color: Int
    ) = TextView(this).apply {
        text = textValue
        textSize = size.toFloat()
        gravity = Gravity.CENTER
        setTextColor(color)
        setPadding(4, 6, 4, 6)
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setSingleLine(true)
        setTextColor(white)
        setHintTextColor(muted)
        setPadding(16, 12, 16, 12)
        setBackgroundColor(Color.rgb(38, 38, 55))
        layoutParams = params()
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(0, 8, 0, 8)
    }

    override fun onDestroy() {
        handler.removeCallbacks(restartListeningRunnable)

        if (receiverRegistered) {
            try {
                unregisterReceiver(diagnosticReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        speechTts?.stop()
        speechTts?.shutdown()
        speechTts = null

        super.onDestroy()
    }
}
