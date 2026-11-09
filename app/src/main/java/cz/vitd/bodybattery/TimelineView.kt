package cz.vitd.bodybattery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Battery level from last night's sleep until now: smooth curve coloured by level,
 * shaded sleep band, hour axis, current-value dot. Touch and drag to read a value.
 */
class TimelineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var points: List<Pair<Long, Int>> = emptyList()
    private var sleepStart: Long = 0
    private var wake: Long = 0
    private var touchX: Float? = null

    private val d = resources.displayMetrics.density
    private val zone = ZoneId.systemDefault()
        private val hh = Texts.hourPattern(context)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255); strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(4 * d, 6 * d), 0f)
    }
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 155, 135, 255) }
    private val sleepText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 190, 175, 255); textSize = 10.5f * d }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255); textSize = 10.5f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1f * d }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x24, 0x2B, 0x35) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; isFakeBoldText = true }

    fun setData(points: List<Pair<Long, Int>>, sleepStart: Long, wake: Long) {
        this.points = points
        this.sleepStart = sleepStart
        this.wake = wake
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchX = null
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val left = 26 * d
        val right = width - 8 * d
        val top = 18 * d
        val bottom = height - 20 * d
        val h = bottom - top
        fun y(l: Int) = bottom - h * l / 100f

        // grid + y labels
        for (lvl in listOf(0, 50, 100)) {
            canvas.drawLine(left, y(lvl), right, y(lvl), grid)
            canvas.drawText(lvl.toString(), 0f, y(lvl) + 4 * d, axisText)
        }
        if (points.size < 2) return

        val t0 = points.first().first
        val t1 = points.last().first
        val span = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(t: Long) = left + (t - t0) / span * (right - left)

        // sleep band
        val sx0 = x(sleepStart.coerceIn(t0, t1))
        val sx1 = x(wake.coerceIn(t0, t1))
        if (sx1 > sx0) {
            canvas.drawRoundRect(RectF(sx0, top - 14 * d, sx1, bottom), 8 * d, 8 * d, sleepPaint)
            val label = "☾ " + context.getString(R.string.chart_sleep)
            if (sx1 - sx0 > sleepText.measureText(label) + 8 * d) canvas.drawText(label, sx0 + 6 * d, top - 2 * d, sleepText)
        }

        // hour labels every 3 hours
        val firstHour = Instant.ofEpochSecond(t0).atZone(zone).withMinute(0).withSecond(0).plusHours(1)
        var tick = firstHour
        while (tick.toEpochSecond() <= t1) {
            if (tick.hour % 3 == 0) {
                val tx = x(tick.toEpochSecond())
                val s = tick.format(hh)
                canvas.drawText(s, (tx - axisText.measureText(s) / 2).coerceIn(left, right - axisText.measureText(s)), height - 4 * d, axisText)
            }
            tick = tick.plusHours(1)
        }

        // smooth curve through the points
        val pts = points.map { x(it.first) to y(it.second) }
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) {
                val (px, py) = pts[i - 1]
                val (cx, cy) = pts[i]
                val mx = (px + cx) / 2
                cubicTo(mx, py, mx, cy, cx, cy)
            }
        }
        val colors = intArrayOf(
            RingRenderer.colorFor(100), RingRenderer.colorFor(60), RingRenderer.colorFor(35), RingRenderer.colorFor(10),
        )
        val stops = floatArrayOf(0f, 0.4f, 0.65f, 1f)
        line.shader = LinearGradient(0f, y(100), 0f, y(0), colors, stops, Shader.TileMode.CLAMP)
        val fillColors = colors.map { Color.argb(70, Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
        fill.shader = LinearGradient(0f, y(100), 0f, bottom, fillColors.map { it }.toIntArray().also {
            it[it.size - 1] = Color.argb(0, Color.red(colors.last()), Color.green(colors.last()), Color.blue(colors.last()))
        }, stops, Shader.TileMode.CLAMP)
        val area = Path(path).apply { lineTo(pts.last().first, bottom); lineTo(pts.first().first, bottom); close() }
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        // current value dot
        val (lx, ly) = pts.last()
        val lastColor = RingRenderer.colorFor(points.last().second)
        halo.color = Color.argb(70, Color.red(lastColor), Color.green(lastColor), Color.blue(lastColor))
        dot.color = lastColor
        canvas.drawCircle(lx, ly, 9 * d, halo)
        canvas.drawCircle(lx, ly, 4.5f * d, dot)

        // touch cursor
        touchX?.let { tx ->
            val cx = tx.coerceIn(left, right)
            val idx = pts.indices.minByOrNull { kotlin.math.abs(pts[it].first - cx) } ?: return@let
            val (px, py) = pts[idx]
            val (pt, pl) = points[idx]
            canvas.drawLine(px, top, px, bottom, cursor)
            dot.color = RingRenderer.colorFor(pl)
            canvas.drawCircle(px, py, 5 * d, dot)
            val text = context.getString(R.string.fmt_tooltip, pl, Texts.time(context, pt))
            val tw = bubbleText.measureText(text) + 16 * d
            val bx = (px - tw / 2).coerceIn(0f, width - tw)
            val by = (py - 36 * d).coerceAtLeast(0f)
            canvas.drawRoundRect(RectF(bx, by, bx + tw, by + 24 * d), 12 * d, 12 * d, bubble)
            canvas.drawText(text, bx + 8 * d, by + 16.5f * d, bubbleText)
        }
    }
}
