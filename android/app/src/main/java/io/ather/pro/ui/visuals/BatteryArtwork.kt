package io.ather.pro.ui.visuals

import androidx.core.graphics.ColorUtils
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Native battery artwork using Material color roles; no vehicle-specific assets. */
class BatteryArtwork(
    private val primary: Int,
    private val onPrimary: Int,
    private val surface: Int,
    private val container: Int,
    private val onSurface: Int,
    private val outline: Int,
    private val tertiary: Int,
    private val error: Int,
    private val onError: Int
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = RectF(28f, 18f, 212f, 202f)
    private val body = RectF(82f, 52f, 158f, 164f)
    private val cell = RectF(90f, 60f, 150f, 156f)
    private val clip = Path().apply { addRoundRect(cell, 12f, 12f, Path.Direction.CW) }
    private val bolt = Path().apply {
        moveTo(123f, 87f); lineTo(106f, 111f); lineTo(119f, 111f)
        lineTo(114f, 131f); lineTo(135f, 104f); lineTo(122f, 104f); close()
    }
    private val shell = LinearGradient(82f, 52f, 160f, 164f,
        intArrayOf(container, ColorUtils.blendARGB(container, surface, .5f), surface), null, Shader.TileMode.CLAMP)
    private val shellEdge = LinearGradient(82f, 52f, 158f, 164f,
        intArrayOf(outline, ColorUtils.blendARGB(outline, surface, .5f), outline), null, Shader.TileMode.CLAMP)
    private val energy = LinearGradient(90f, 60f, 150f, 156f,
        intArrayOf(ColorUtils.blendARGB(primary, onSurface, .15f), primary, ColorUtils.blendARGB(primary, surface, .3f)), null, Shader.TileMode.CLAMP)
    private val lowEnergy = LinearGradient(90f, 60f, 150f, 156f,
        error, ColorUtils.blendARGB(error, surface, .3f), Shader.TileMode.CLAMP)
    private val halo = RadialGradient(120f, 110f, 110f,
        intArrayOf(ColorUtils.setAlphaComponent(primary, 38), ColorUtils.setAlphaComponent(primary, 14), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val arc = LinearGradient(28f, 202f, 212f, 18f,
        ColorUtils.blendARGB(primary, surface, .3f), primary, Shader.TileMode.CLAMP)

    private fun ink(color: Int, stroke: Float = 0f, shader: Shader? = null, alpha: Int = 255): Paint {
        paint.color = color
        paint.alpha = Color.alpha(color) * alpha / 255
        paint.shader = shader
        paint.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        return paint
    }

    fun draw(canvas: Canvas, width: Float, height: Float, soc: Double?, charging: Boolean,
        limitPercent: Int? = null, phase: Float = 0f) {
        val level = soc?.takeIf { it.isFinite() && it in 0.0..100.0 }?.toFloat()?.div(100f)
        val low = level != null && level < 0.2f
        val accent = if (low) error else primary
        val scale = min(width, height) / 240f
        val saved = canvas.save()
        canvas.translate((width - 240f * scale) / 2, (height - 240f * scale) / 2)
        canvas.scale(scale, scale)

        canvas.drawCircle(120f, 110f, 110f, ink(Color.WHITE, shader = halo))
        canvas.drawCircle(120f, 110f, 104f, ink(ColorUtils.setAlphaComponent(onSurface, 13), stroke = 1f))
        repeat(24) { index ->
            val angle = Math.toRadians(index * 15.0 - 90.0)
            val inner = if (index % 6 == 0) 98f else 101f
            canvas.drawLine(120f + cos(angle).toFloat() * inner, 110f + sin(angle).toFloat() * inner,
                120f + cos(angle).toFloat() * 104f, 110f + sin(angle).toFloat() * 104f,
                ink(ColorUtils.setAlphaComponent(outline, 53), stroke = 1.2f))
        }
        canvas.drawArc(ring, -90f, 360f, false, ink(outline, stroke = 3.5f))
        if (level != null && level > 0) {
            canvas.drawArc(ring, -90f, 360f * level, false,
                ink(accent, stroke = 3.5f, shader = if (low) null else arc))
            val end = Math.toRadians(level * 360.0 - 90.0)
            val x = 120f + cos(end).toFloat() * 92f
            val y = 110f + sin(end).toFloat() * 92f
            canvas.drawCircle(x, y, 6f, ink(accent, alpha = 28))
            canvas.drawCircle(x, y, 2.8f, ink(onSurface))
        }
        limitPercent?.coerceIn(0, 100)?.let { limit ->
            val angle = Math.toRadians(limit * 3.6 - 90.0)
            canvas.drawLine(120f + cos(angle).toFloat() * 86f, 110f + sin(angle).toFloat() * 86f,
                120f + cos(angle).toFloat() * 99f, 110f + sin(angle).toFloat() * 99f,
                ink(tertiary, stroke = 2.4f))
        }

        canvas.drawRoundRect(87f, 56f, 163f, 170f, 20f, 20f, ink(0x55000000))
        canvas.drawRoundRect(106f, 44f, 134f, 54f, 4f, 4f, ink(outline))
        canvas.drawRoundRect(110f, 45f, 130f, 49f, 2f, 2f, ink(onSurface))
        canvas.drawRoundRect(body, 20f, 20f, ink(Color.WHITE, shader = shell))
        canvas.drawRoundRect(body, 20f, 20f, ink(Color.WHITE, stroke = 1.4f, shader = shellEdge))
        canvas.drawRoundRect(cell, 12f, 12f, ink(surface))
        val clipped = canvas.save()
        canvas.clipPath(clip)
        if (level != null) {
            val top = cell.bottom - cell.height() * level
            canvas.drawRect(cell.left, top, cell.right, cell.bottom,
                ink(Color.WHITE, shader = if (low) lowEnergy else energy))
            if (level > 0) canvas.drawLine(cell.left, top, cell.right, top,
                ink(ColorUtils.blendARGB(accent, onSurface, .3f), stroke = 1.2f))
            if (charging && level > 0) {
                val shineY = cell.bottom - phase * cell.height()
                canvas.clipRect(cell.left, top, cell.right, cell.bottom)
                canvas.drawRect(cell.left, shineY - 9f, cell.right, shineY + 9f,
                    ink(Color.WHITE, alpha = (18f * sin(phase * Math.PI)).toInt().coerceIn(0, 18)))
            }
        }
        repeat(4) { index ->
            val y = cell.top + cell.height() * (index + 1) / 5
            canvas.drawLine(cell.left, y, cell.right, y, ink(ColorUtils.setAlphaComponent(surface, 70), stroke = 1.2f))
        }
        canvas.drawRect(91f, 60f, 99f, 156f, ink(0x15FFFFFF))
        canvas.restoreToCount(clipped)
        canvas.drawRoundRect(cell, 12f, 12f, ink(ColorUtils.setAlphaComponent(primary, 40), stroke = 1f))
        if (charging) {
            canvas.drawPath(bolt, ink(if (low) onError else onPrimary))
            canvas.drawLine(120f, 174f, 120f, 198f, ink(ColorUtils.setAlphaComponent(primary, 48), stroke = 1.6f))
            repeat(3) { index ->
                val travel = (phase + index / 3f) % 1f
                canvas.drawCircle(120f, 198f - travel * 24f, 1.8f,
                    ink(accent, alpha = (sin(travel * Math.PI) * 180).toInt().coerceIn(0, 180)))
            }
        } else {
            canvas.drawRoundRect(112f, 175f, 128f, 178f, 1.5f, 1.5f, ink(ColorUtils.setAlphaComponent(outline, 80)))
        }
        canvas.restoreToCount(saved)
    }
}
