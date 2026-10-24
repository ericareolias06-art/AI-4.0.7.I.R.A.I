
package com.iraitec

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val api = "https://ai-4-0-7-i-r-a-i-1.onrender.com"
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

    private var registering = false
    private var currentEmail = ""
    private var receiverRegistered = false
    private var latestDiagnostic = "Waiting for listening service..."

    private val diagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != IraiListeningService.ACTION_DIAGNOSTIC) return

            val status = intent.getStringExtra(
                IraiListeningService.EXTRA_STATUS
            ) ?: return

            runOnUiThread {
                latestDiagnostic = status
                if (::diagnosticText.isInitialized) {
                    diagnosticText.text = "WAKE-WORD DIAGNOSTICS\n$status"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieHandler.setDefault(
            CookieManager(null, CookiePolicy.ACCEPT_ALL)
        )

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val filter = IntentFilter(IraiListeningService.ACTION_DIAGNOSTIC)

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

        showAuth()
    }

    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1002
            )
        } else {
            requestMicrophonePermissionAfterNotifications()
        }
    }

    private fun requestMicrophonePermissionAfterNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1001
            )
        } else {
            startIraiListeningService()
        }
    }

    private fun startIraiListeningService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            latestDiagnostic = "Microphone permission has not been granted."
            refreshDiagnostic()
            return
        }

        try {
            val serviceIntent = Intent(this, IraiListeningService::class.java)

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

            Toast.makeText(
                this,
                latestDiagnostic,
                Toast.LENGTH_LONG
            ).show()
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

                    Toast.makeText(
                        this,
                        "Microphone permission is needed for Irai voice features.",
                        Toast.LENGTH_LONG
                    ).show()
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
            label("Sign in to your Irai Tec account", 18, white).apply {
                setPadding(0, 28, 0, 20)
            }
        )

        emailInput = field("Email address")
        emailInput.inputType = 33
        content.addView(emailInput)

        passwordInput = field("Password")
        passwordInput.inputType = 129
        content.addView(passwordInput)

        confirmInput = field("Confirm password")
        confirmInput.inputType = 129
        confirmInput.visibility = View.GONE
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

                text =
                    if (registering) {
                        "Already have an account? Log in"
                    } else {
                        "Don't have an account? Register"
                    }
            }
        }
        content.addView(toggle)

        message = label("", 14, muted)
        content.addView(message)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun label(textValue: String, size: Int, color: Int) =
        TextView(this).apply {
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

        val endpoint =
            if (registering) "/api/register" else "/api/login"

        val body = JSONObject().apply {
            put("email", email)
            put("password", password)

            if (registering) {
                put("confirmPassword", confirm)
            }
        }

        thread {
            var result = ""
            var success = false

            try {
                val connection =
                    (URL(api + endpoint).openConnection()
                        as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream =
                    if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                result =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""

                success = code in 200..299
                connection.disconnect()
            } catch (_: Exception) {
                result =
                    "Could not connect. Check your internet and try again."
            }

            runOnUiThread {
                val responseJson = try {
                    JSONObject(result)
                } catch (_: Exception) {
                    JSONObject()
                }

                val responseText = responseJson.optString(
                    "message",
                    responseJson.optString("error", result)
                )

                message.text = responseText

                if (success && registering) {
                    registering = false
                    confirmInput.visibility = View.GONE
                    passwordInput.text.clear()
                    message.text = "Account created! You can now log in."
                } else if (success) {
                    currentEmail = email
                    showHome(email)
                    requestMicrophonePermission()
                }
            }
        }
    }

    private fun showHome(email: String) {
        thread {
            var premium = false
            var statusMessage = ""

            try {
                val connection =
                    (URL("$api/api/me").openConnection()
                        as HttpURLConnection)

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val result =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
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

                root.addView(label("✦", 48, accent))
                root.addView(label("Welcome to IRAI TEC", 26, white))
                root.addView(label("Signed in as $email", 15, muted))

                if (statusMessage.isNotBlank()) {
                    root.addView(label(statusMessage, 15, muted))
                } else if (premium) {
                    root.addView(label("✓ Premium Active", 22, accent))
                } else {
                    root.addView(label("Unlock IRAI TEC Premium", 20, white))
                    root.addView(label("$3/month", 18, accent))

                    root.addView(Button(this).apply {
                        text = "Subscribe Monthly"
                        setOnClickListener {
                            startCheckout("monthly")
                        }
                    }, params())

                    root.addView(label("$30/year", 18, accent))

                    root.addView(Button(this).apply {
                        text = "Subscribe Yearly"
                        setOnClickListener {
                            startCheckout("yearly")
                        }
                    }, params())

                    root.addView(Button(this).apply {
                        text = "Redeem Promo Code"
                        setOnClickListener {
                            showPromoInput()
                        }
                    }, params())
                }

                val diagnosticPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.rgb(30, 30, 48))
                }

                diagnosticPanel.addView(label("WAKE-WORD DIAGNOSTICS", 16, accent))

                diagnosticText = TextView(this).apply {
                    text = latestDiagnostic
                    textSize = 14f
                    setTextColor(white)
                    gravity = Gravity.CENTER
                    setPadding(8, 8, 8, 8)
                }

                diagnosticPanel.addView(diagnosticText)
                root.addView(diagnosticPanel, params())

                root.addView(Button(this).apply {
                    text = "Restart Listening"
                    setOnClickListener {
                        latestDiagnostic = "Restarting listening service..."
                        refreshDiagnostic()
                        startIraiListeningService()
                    }
                }, params())

                root.addView(Button(this).apply {
                    text = "Log Out"
                    setOnClickListener {
                        logout()
                    }
                }, params())

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
            this,
            "Preparing secure checkout...",
            Toast.LENGTH_SHORT
        ).show()

        thread {
            var checkoutUrl = ""
            var errorMessage = ""

            try {
                val body = JSONObject().put("plan", plan)

                val connection =
                    (URL("$api/api/create-checkout-session")
                        .openConnection() as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream =
                    if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val result =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""

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
                        json.optString(
                            "message",
                            "Could not start checkout."
                        )
                    )
                }

                connection.disconnect()
            } catch (_: Exception) {
                errorMessage =
                    "Could not connect to checkout. Please try again."
            }

            runOnUiThread {
                if (checkoutUrl.startsWith("https://")) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(checkoutUrl)
                        )
                    )
                } else {
                    Toast.makeText(
                        this,
                        if (errorMessage.isNotBlank()) {
                            errorMessage
                        } else {
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
            Toast.makeText(
                this,
                "Enter a promo code.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Checking promo code...",
            Toast.LENGTH_SHORT
        ).show()

        thread {
            var responseMessage = "Could not redeem promo code."
            var success = false

            try {
                val body = JSONObject().put("code", code)

                val connection =
                    (URL("$api/api/redeem-promo")
                        .openConnection() as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val status = connection.responseCode
                val stream =
                    if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val result =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""

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
                        if (success) {
                            "Promo code redeemed."
                        } else {
                            "Could not redeem promo code."
                        }
                    )
                )

                connection.disconnect()
            } catch (_: Exception) {
                responseMessage =
                    "Could not connect. Please try again."
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    responseMessage,
                    Toast.LENGTH_LONG
                ).show()

                if (success) {
                    showHome(currentEmail)
                }
            }
        }
    }

    private fun logout() {
        thread {
            try {
                val connection =
                    (URL("$api/api/logout")
                        .openConnection() as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                connection.responseCode
                connection.disconnect()
            } catch (_: Exception) {
                // Return to the login screen even if logout fails.
            }

            runOnUiThread {
                currentEmail = ""
                latestDiagnostic = "Waiting for listening service..."
                showAuth()
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(diagnosticReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
