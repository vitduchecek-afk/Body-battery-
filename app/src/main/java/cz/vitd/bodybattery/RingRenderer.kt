package cz.vitd.bodybattery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

object RingRenderer {
    fun colorFor(level: Int): Int = when {
        level >= 76 -> Color.rgb(0x2F, 0x80, 0xED)
        level >= 51 -> Color.rgb(0x27, 0xAE, 0x9F)
        level >= 26 -> Color.rgb(0xF2, 0x99, 0x4A)
        else -> Color.rgb(0xEB, 0x57, 0x57)
    }

    /** Draws the battery ring with the number in the middle. [level] null = no data. */
    fun render(sizePx: Int, level: Int?, caption: String?): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = sizePx * 0.09f
        val pad = stroke / 2 + sizePx * 0.03f
        val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.argb(60, 255, 255, 255); strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(rect, 135f, 270f, false, track)
        if (level != null) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = colorFor(level); strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(rect, 135f, 270f * level.coerceIn(0, 100) / 100f, false, arc)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.30f
        }
        val cy = sizePx / 2f - (text.descent() + text.ascent()) / 2 - sizePx * 0.02f
        c.drawText(level?.toString() ?: "–", sizePx / 2f, cy, text)
        if (caption != null) {
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = sizePx * 0.085f
            }
            c.drawText(caption, sizePx / 2f, sizePx * 0.86f, small)
        }
        return bmp
    }
}
