package cz.vitd.bodybattery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Line chart of the battery level from last night's sleep until now. */
class TimelineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var points: List<Pair<Long, Int>> = emptyList()
    private var wake: Long = 0
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.rgb(0x2F, 0x80, 0xED) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(50, 0x2F, 0x80, 0xED) }
    private val grid = Paint().apply { color = Color.argb(40, 255, 255, 255); strokeWidth = density }
    private val sleepBg = Paint().apply { color = Color.argb(30, 150, 120, 255) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255); textSize = 11 * density }
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")

    fun setData(points: List<Pair<Long, Int>>, wake: Long) {
        this.points = points
        this.wake = wake
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat() - 16 * density
        for (lvl in listOf(0, 25, 50, 75, 100)) {
            val y = h - h * lvl / 100f
            canvas.drawLine(0f, y, w, y, grid)
        }
        if (points.size < 2) return
        val t0 = points.first().first
        val t1 = points.last().first
        val span = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(t: Long) = (t - t0) / span * w
        fun y(l: Int) = h - h * l / 100f
        if (wake in t0..t1) canvas.drawRect(0f, 0f, x(wake), h, sleepBg)
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
        val area = Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        val zone = ZoneId.systemDefault()
        canvas.drawText(Instant.ofEpochSecond(t0).atZone(zone).format(fmt), 0f, height - 2 * density, label)
        val end = Instant.ofEpochSecond(t1).atZone(zone).format(fmt)
        canvas.drawText(end, w - label.measureText(end), height - 2 * density, label)
        if (wake in t0..t1) {
            val s = "probuzení " + Instant.ofEpochSecond(wake).atZone(zone).format(fmt)
            canvas.drawText(s, (x(wake) - label.measureText(s) / 2).coerceIn(0f, w - label.measureText(s)), 12 * density, label)
        }
    }
}
