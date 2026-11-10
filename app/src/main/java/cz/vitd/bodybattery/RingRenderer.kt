package cz.vitd.bodybattery

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

object RingRenderer {
    // Same values as charge_* in colors.xml (mint → lime → amber → coral).
    private val HIGH = Color.rgb(0x3D, 0xDC, 0x97)
    private val MEDIUM = Color.rgb(0xB5, 0xE3, 0x5A)
    private val LOW = Color.rgb(0xF5, 0xB0, 0x41)
    private val VERY_LOW = Color.rgb(0xF0, 0x60, 0x5D)

    fun colorFor(level: Int): Int = when {
        level >= 76 -> HIGH
        level >= 51 -> MEDIUM
        level >= 26 -> LOW
        else -> VERY_LOW
    }

    /** Colour of the VO2 max dial (violet, clearly different from the charge palette). */
    val VO2_COLOR = Color.rgb(0x9B, 0x8C, 0xFF)

    /** VO2 max dial: same look as the charge dial, ring filled on a 20–80 ml/kg/min scale. */
    fun renderVo2(sizePx: Int, vo2: Double?, valueText: String, caption: String?): Bitmap {
        val fraction = vo2?.let { ((it - 20.0) / 60.0).coerceIn(0.03, 1.0).toFloat() }
        return gauge(sizePx, fraction, valueText, caption, VO2_COLOR, glow = true, numberScale = if (valueText.length > 3) 0.8f else 1f)
    }

    /**
     * Draws the charge ring with the number in the middle.
     * [level] null = no data. [caption] small text under the number. [glow] soft glow (app only).
     */
    fun render(sizePx: Int, level: Int?, caption: String?, glow: Boolean = false): Bitmap =
        gauge(sizePx, level?.let { it.coerceIn(0, 100) / 100f }, level?.toString() ?: "–", caption, level?.let { colorFor(it) }, glow)

    private fun gauge(sizePx: Int, fraction: Float?, valueText: String, caption: String?, col: Int?, glow: Boolean, numberScale: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = sizePx * 0.085f
        val pad = stroke / 2 + sizePx * 0.06f
        val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.argb(40, 255, 255, 255); strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(rect, 135f, 270f, false, track)
        if (fraction != null && col != null) {
            val sweep = 270f * fraction
            if (glow) {
                val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = stroke * 1.4f; strokeCap = Paint.Cap.ROUND
                    color = Color.argb(110, Color.red(col), Color.green(col), Color.blue(col))
                    maskFilter = BlurMaskFilter(sizePx * 0.04f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawArc(rect, 135f, sweep, false, g)
            }
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = col; strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(rect, 135f, sweep, false, arc)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.28f * numberScale
        }
        val cy = sizePx / 2f - (text.descent() + text.ascent()) / 2 - sizePx * 0.03f
        c.drawText(valueText, sizePx / 2f, cy, text)
        if (caption != null) {
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (fraction != null && col != null && glow) col else Color.argb(200, 255, 255, 255)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = sizePx * 0.075f
                // shrink long captions (some languages) to fit inside the ring
                val maxW = sizePx * 0.5f
                val w = measureText(caption)
                if (w > maxW) textSize *= maxW / w
            }
            c.drawText(caption, sizePx / 2f, sizePx * 0.86f, small)
        }
        return bmp
    }
}
