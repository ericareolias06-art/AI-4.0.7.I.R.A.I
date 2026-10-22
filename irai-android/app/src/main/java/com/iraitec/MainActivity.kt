
package com.iraitec

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONObject
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
    private var registering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showAuth()
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
        content.addView(label("Sign in to your Irai Tec account", 18, white).apply {
            setPadding(0, 28, 0, 20)
        })

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
                action.text = if (registering) "Create Account" else "Log In"
                confirmInput.visibility =
                    if (registering) View.VISIBLE else View.GONE
                message.text = ""
                (it as TextView).text =
                    if (registering) "Already have an account? Log in"
                    else "Don't have an account? Register"
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
        val endpoint = if (registering) "/api/register" else "/api/login"
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            if (registering) put("confirmPassword", confirm)
        }

        thread {
            var result = ""
            var success = false
            try {
                val connection = (URL(api + endpoint).openConnection()
                    as HttpURLConnection)
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
                val stream = if (code in 200..299)
                    connection.inputStream else connection.errorStream
                result = stream?.bufferedReader()?.use { it.readText() } ?: ""
                success = code in 200..299
                connection.disconnect()
            } catch (e: Exception) {
                result = "Could not connect. Check your internet and try again."
            }

            runOnUiThread {
                val text = try {
                    val json = JSONObject(result)
                    json.optString(
                        "message",
                        json.optString("error", result)
                    )
                } catch (_: Exception) {
                    result
                }
                message.text = text
                if (success && registering) {
                    registering = false
                    confirmInput.visibility = View.GONE
                    passwordInput.text.clear()
                    message.text = "Account created! You can now log in."
                } else if (success) {
                    showHome(email)
                }
            }
        }
    }

    private fun showHome(email: String) {
        root.removeAllViews()
        root.gravity = Gravity.CENTER
        root.addView(label("✦", 58, accent))
        root.addView(label("Welcome to IRAI TEC", 26, white))
        root.addView(label("Signed in as $email", 15, muted))
        root.addView(label("Premium plans are coming next.", 16, white))
        root.addView(Button(this).apply {
            text = "Log Out"
            setOnClickListener { showAuth() }
        }, params())
    }
}
