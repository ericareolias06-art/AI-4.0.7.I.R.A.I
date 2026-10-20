
package com.iraitec

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(18, 18, 32)
        window.navigationBarColor = Color.rgb(18, 18, 32)
        window.decorView.systemUiVisibility = 0

        val background = Color.rgb(18, 18, 32)
        val accent = Color.rgb(150, 120, 255)
        val white = Color.WHITE
        val muted = Color.rgb(190, 190, 210)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(background)
        }

        val logo = TextView(this).apply {
            text = "✦"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(accent)
        }

        val title = TextView(this).apply {
            text = "IRAI TEC"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        val subtitle = TextView(this).apply {
            text = "Your AI companion"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }

        val welcome = TextView(this).apply {
            text = "\nHello! I'm Irai.\n\nI'm glad you're here.\nLet's build something amazing together."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(8, 28, 8, 28)
        }

        val status = TextView(this).apply {
            text = "YOUR JOURNEY STARTS HERE"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(12, 24, 12, 12)
        }

        layout.addView(logo)
        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(welcome)
        layout.addView(status)

        setContentView(layout)
    }
}