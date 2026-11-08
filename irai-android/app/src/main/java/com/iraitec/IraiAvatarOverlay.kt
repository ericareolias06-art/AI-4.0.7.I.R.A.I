package com.iraitec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lightweight always-on-top companion. It does not access the microphone;
 * wake-word and speech recognition remain in the existing listening flow.
 */
object IraiAvatarOverlay {
    private var windowManager: WindowManager? = null
    private var avatarWindow: LinearLayout? = null
    private var minimizedWindow: TextView? = null
    private var statusLabel: TextView? = null
    private var appContext: Context? = null

    private fun dp(value: Int): Int {
        val context = appContext ?: return value
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun params(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width, height, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(22)
        }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (!hasPermission(context)) return
        appContext = context.applicationContext
        windowManager = appContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (avatarWindow != null) return
        removeMinimized()

        val density = appContext!!.resources.displayMetrics.density
        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(35, 18, 18, 32))
                cornerRadius = 18 * density
            }
        }
        statusLabel = TextView(appContext).apply {
            text = "Irai is here"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(205, 35, 29, 58))
                cornerRadius = 20 * density
            }
        }
        panel.addView(statusLabel)

        val avatar = ImageView(appContext).apply {
            setImageResource(R.drawable.irai_avatar)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Irai floating companion"
        }
        panel.addView(avatar, LinearLayout.LayoutParams(dp(132), dp(198)))

        val controls = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val minimize = TextView(appContext).apply {
            text = "Minimize"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill(density)
            setOnClickListener { minimize() }
        }
        val dismiss = TextView(appContext).apply {
            text = "×"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            background = pill(density)
            setOnClickListener { hide() }
        }
        controls.addView(minimize)
        controls.addView(dismiss)
        panel.addView(controls)
        avatarWindow = panel
        try {
            windowManager?.addView(panel, params(dp(146), dp(252)))
        } catch (_: Exception) {
            avatarWindow = null
        }
    }

    private fun pill(density: Float) = GradientDrawable().apply {
        setColor(Color.argb(220, 74, 57, 112))
        cornerRadius = 18 * density
    }

    fun setStatus(value: String) {
        statusLabel?.post { statusLabel?.text = value }
    }

    fun minimize() {
        val context = appContext ?: return
        removeAvatar()
        if (minimizedWindow != null) return
        val bubble = TextView(context).apply {
            text = "I"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.rgb(105, 76, 165))
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(220, 195, 255))
            }
            setOnClickListener { show(context) }
            contentDescription = "Restore Irai companion"
        }
        minimizedWindow = bubble
        try {
            windowManager?.addView(bubble, params(dp(54), dp(54)))
        } catch (_: Exception) {
            minimizedWindow = null
        }
    }

    fun hide() {
        removeAvatar()
        removeMinimized()
    }

    private fun removeAvatar() {
        try { avatarWindow?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        avatarWindow = null
        statusLabel = null
    }

    private fun removeMinimized() {
        try { minimizedWindow?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        minimizedWindow = null
    }
}
