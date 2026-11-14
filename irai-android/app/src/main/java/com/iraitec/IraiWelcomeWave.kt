package com.iraitec

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.io.File

/**
 * Plays the user's original 21-frame waving GIF once, inside the *same*
 * ImageView and therefore the same avatar bounds. No zooming, reshaping,
 * looping or replacement of the permanent avatar resource.
 *
 * Movie decoding runs once. The lightweight drawable advances on the UI
 * thread for about 2.5 seconds and restores the original avatar at the end.
 */
class IraiWelcomeWave(
    private val movie: Movie,
    private val whenFinished: () -> Unit
) : Drawable(), Runnable {
    private val clipMillis = (if (movie.duration() > 0) movie.duration() else 2520).coerceAtMost(10_000)
    private var started = 0L
    private var stopped = false

    override fun draw(canvas: Canvas) {
        if (stopped) return
        val now = SystemClock.uptimeMillis()
        if (started == 0L) started = now
        val elapsed = (now - started).toInt()
        if (elapsed >= clipMillis) {
            stop()
            whenFinished()
            return
        }

        movie.setTime(elapsed.coerceAtLeast(0))
        val b = bounds
        val nativeW = movie.width().coerceAtLeast(1).toFloat()
        val nativeH = movie.height().coerceAtLeast(1).toFloat()
        val scale = minOf(b.width() / nativeW, b.height() / nativeH)
        val drawX = b.left + (b.width() - nativeW * scale) / 2f
        val drawY = b.top + (b.height() - nativeH * scale) / 2f
        val save = canvas.save()
        canvas.translate(drawX, drawY)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restoreToCount(save)
        scheduleSelf(this, now + 33L)
    }

    override fun run() { invalidateSelf() }

    fun stop() {
        if (stopped) return
        stopped = true
        unscheduleSelf(this)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val FILE_NAME = "irai_welcome_wave.gif"

        fun gifFile(context: Context) = File(context.filesDir, FILE_NAME)

        /** Bundled asset is preferred: no user import or setup required. */
        fun loaded(context: Context, whenFinished: () -> Unit): IraiWelcomeWave? {
            val bundled = try {
                context.assets.open("irai_welcome_wave.gif").use { stream ->
                    Movie.decodeStream(stream)
                }
            } catch (_: Exception) { null }
            if (bundled != null) return IraiWelcomeWave(bundled, whenFinished)
            // Compatibility with users who imported a GIF in an older build.
            val legacy = gifFile(context)
            if (!legacy.isFile || legacy.length() !in 1_000L..8_000_000L) return null
            return try {
                Movie.decodeFile(legacy.absolutePath)?.let { IraiWelcomeWave(it, whenFinished) }
            } catch (_: Exception) { null }
        }
    }
}
