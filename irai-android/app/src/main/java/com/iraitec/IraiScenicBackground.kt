package com.iraitec

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat

/**
 * Original locally rendered themed scenery. No copyrighted artwork, network
 * images, or additional RAM-heavy background bitmaps are required.
 */
class IraiScenicBackground(private val ocean: Boolean) : Drawable() {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val scale = w / 420f
        canvas.scale(scale, scale)
        val H = h / scale

        val base = if (ocean) intArrayOf(0xFF02192C.toInt(), 0xFF075C80.toInt(),
            0xFF08354A.toInt(), 0xFF061829.toInt()) else intArrayOf(
            0xFF0A061D.toInt(), 0xFF221049.toInt(), 0xFF120B29.toInt(), 0xFF030618.toInt())
        brush.shader = LinearGradient(0f, 0f, 320f, H, base, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 420f, H, brush)
        brush.shader = null

        if (ocean) drawOcean(canvas, H) else drawCosmos(canvas, H)
        canvas.restore()
    }

    private fun orb(canvas: Canvas, x: Float, y: Float, radius: Float, center: Int, edge: Int) {
        brush.shader = RadialGradient(x - radius * .3f, y - radius * .35f, radius * 1.5f,
            intArrayOf(center, edge, 0x00000000), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, brush)
        brush.shader = null
    }

    private fun drawCosmos(canvas: Canvas, h: Float) {
        // Crimson and violet nebulae occupy the edges to keep UI text legible.
        orb(canvas, 12f, h * .27f, 170f, 0x80E1245C.toInt(), 0x443C1D93)
        orb(canvas, 390f, h * .68f, 205f, 0x85B21B43.toInt(), 0x443A1889)
        orb(canvas, 330f, h * .15f, 135f, 0x55802CC9, 0x33231A83)
        val rng = java.util.Random(6072L)
        for (i in 0 until 165) {
            val x = rng.nextFloat() * 420f
            val y = rng.nextFloat() * h
            brush.color = if (i % 6 == 0) 0xB0DBBBFF.toInt() else 0x7BCBD5FF
            canvas.drawCircle(x, y, if (i % 17 == 0) 1.7f else .65f, brush)
        }
        // Atmospheric planet with a rim glow and small satellite.
        orb(canvas, 352f, h * .12f, 81f, 0xE07A68DA.toInt(), 0xCC1A0D39.toInt())
        brush.style = Paint.Style.STROKE
        brush.strokeWidth = 2.1f
        brush.color = 0xB9DC9BFF.toInt()
        canvas.drawCircle(352f, h * .12f, 81f, brush)
        brush.style = Paint.Style.FILL
        orb(canvas, 42f, h * .73f, 35f, 0xDDAB436B.toInt(), 0xBB220C40.toInt())
        // Thin flowing red nebula filaments.
        for (i in 0..3) {
            val p = Path()
            val y = h * (.28f + i * .023f)
            p.moveTo(-30f, y)
            p.cubicTo(115f, y - 100f, 225f, y + 115f, 480f, y - 70f)
            brush.color = if (i % 2 == 0) 0x88C13B70.toInt() else 0x556D3ACA
            brush.style = Paint.Style.STROKE
            brush.strokeWidth = if (i == 0) 6f else 2.4f
            canvas.drawPath(p, brush)
        }
        brush.style = Paint.Style.FILL
    }

    private fun drawOcean(canvas: Canvas, h: Float) {
        // Underwater sunlight, layered current patterns and ocean depth.
        val center = 0x9976E8FF.toInt()
        orb(canvas, 190f, -45f, 210f, center, 0x2557B8DE)
        brush.style = Paint.Style.STROKE
        for (i in 0..8) {
            brush.color = if (i % 2 == 0) 0x4289EAFF else 0x2268C0E6
            brush.strokeWidth = 5f + (i % 3)
            val y = h * (.16f + i * .096f)
            val p = Path()
            p.moveTo(-40f, y)
            p.cubicTo(45f, y - 54f, 112f, y + 60f, 216f, y + 5f)
            p.cubicTo(301f, y - 45f, 343f, y + 45f, 470f, y - 15f)
            canvas.drawPath(p, brush)
        }
        brush.style = Paint.Style.FILL
        val rng = java.util.Random(1221L)
        for (i in 0 until 55) {
            val x = rng.nextFloat() * 420f
            val y = rng.nextFloat() * h
            val r = 1f + rng.nextFloat() * 4f
            brush.color = 0x428DEEFF
            canvas.drawCircle(x, y, r, brush)
        }
        // Curved deep-sea floor silhouettes.
        for (layer in 0..2) {
            val top = h * (.75f + layer * .07f)
            val p = Path()
            p.moveTo(0f, h)
            p.lineTo(0f, top)
            p.cubicTo(110f, top - 40f, 260f, top + 70f, 420f, top - 12f)
            p.lineTo(420f, h)
            p.close()
            brush.color = when (layer) {
                0 -> 0x6714727E
                1 -> 0xAA06384C.toInt()
                else -> 0xCC041F32.toInt()
            }
            canvas.drawPath(p, brush)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated by Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
