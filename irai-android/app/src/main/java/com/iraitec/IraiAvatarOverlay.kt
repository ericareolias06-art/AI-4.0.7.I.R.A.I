package com.iraitec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
    private var avatarImage: ImageView? = null
    private var idleAnimation: AnimatorSet? = null
    private var speakingAnimation: AnimatorSet? = null
    private var avatarLayoutParams: WindowManager.LayoutParams? = null

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
            gravity = Gravity.TOP or Gravity.START
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
            contentDescription = "Irai animated floating companion"
        }
        avatarImage = avatar
        var dragDownX = 0f
        var dragDownY = 0f
        var dragStartX = 0
        var dragStartY = 0
        avatar.setOnTouchListener { _, event ->
            val lp = avatarLayoutParams
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragStartX = lp?.x ?: 0
                    dragStartY = lp?.y ?: 0
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (lp != null) {
                        val screen = appContext?.resources?.displayMetrics
                        val maxX = ((screen?.widthPixels ?: 0) - dp(146)).coerceAtLeast(0)
                        val maxY = ((screen?.heightPixels ?: 0) - dp(252)).coerceAtLeast(0)
                        lp.x = (dragStartX + event.rawX - dragDownX).toInt().coerceIn(0, maxX)
                        lp.y = (dragStartY + event.rawY - dragDownY).toInt().coerceIn(0, maxY)
                        try { windowManager?.updateViewLayout(avatarWindow, lp) } catch (_: Exception) {}
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
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
            avatarLayoutParams = params(dp(146), dp(252))
            windowManager?.addView(panel, avatarLayoutParams)
            startIdleAnimation()
        } catch (_: Exception) {
            avatarWindow = null
        }
    }

    private fun pill(density: Float) = GradientDrawable().apply {
        setColor(Color.argb(220, 74, 57, 112))
        cornerRadius = 18 * density
    }

    fun setStatus(value: String) {
        statusLabel?.post {
            statusLabel?.text = value
            if (value.startsWith("Speaking", ignoreCase = true)) {
                startSpeakingAnimation()
            } else {
                stopSpeakingAnimation()
                startIdleAnimation()
            }
        }
    }

    // Gentle breathing and floating while Irai is waiting or listening.
    private fun startIdleAnimation() {
        val avatar = avatarImage ?: return
        if (speakingAnimation?.isRunning == true || idleAnimation?.isRunning == true) return
        val floatMotion = ObjectAnimator.ofFloat(avatar, View.TRANSLATION_Y, dp(2).toFloat(), dp(-5).toFloat()).apply {
            duration = 1900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val breathe = ObjectAnimator.ofFloat(avatar, View.SCALE_Y, 1f, 1.018f).apply {
            duration = 1900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        idleAnimation = AnimatorSet().apply {
            playTogether(floatMotion, breathe)
            start()
        }
    }

    // Rhythmic upper-body motion while TTS speaks. Facial mouth frames can
    // be layered in later without replacing the original avatar artwork.
    private fun startSpeakingAnimation() {
        stopIdleAnimation()
        val avatar = avatarImage ?: return
        if (speakingAnimation?.isRunning == true) return
        val pulse = ObjectAnimator.ofFloat(avatar, View.SCALE_X, 1f, 1.025f, 1f).apply {
            duration = 430
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val bob = ObjectAnimator.ofFloat(avatar, View.TRANSLATION_Y, 0f, dp(-3).toFloat(), 0f).apply {
            duration = 430
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        speakingAnimation = AnimatorSet().apply {
            playTogether(pulse, bob)
            start()
        }
    }

    private fun stopSpeakingAnimation() {
        speakingAnimation?.cancel()
        speakingAnimation = null
        avatarImage?.scaleX = 1f
        avatarImage?.translationY = 0f
    }

    private fun stopIdleAnimation() {
        idleAnimation?.cancel()
        idleAnimation = null
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
        stopIdleAnimation()
        stopSpeakingAnimation()
        avatarImage = null
        avatarLayoutParams = null
        try { avatarWindow?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        avatarWindow = null
        statusLabel = null
    }

    private fun removeMinimized() {
        try { minimizedWindow?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        minimizedWindow = null
    }
}
