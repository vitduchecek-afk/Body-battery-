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

    /**
     * Draws the charge ring with the number in the middle.
     * [level] null = no data. [caption] small text under the number. [glow] soft glow (app only).
     */
    fun render(sizePx: Int, level: Int?, caption: String?, glow: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = sizePx * 0.085f
        val pad = stroke / 2 + sizePx * 0.06f
        val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.argb(40, 255, 255, 255); strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(rect, 135f, 270f, false, track)
        if (level != null) {
            val sweep = 270f * level.coerceIn(0, 100) / 100f
            val col = colorFor(level)
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
            textSize = sizePx * 0.28f
        }
        val cy = sizePx / 2f - (text.descent() + text.ascent()) / 2 - sizePx * 0.03f
        c.drawText(level?.toString() ?: "–", sizePx / 2f, cy, text)
        if (caption != null) {
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (level != null && glow) colorFor(level) else Color.argb(200, 255, 255, 255)
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
