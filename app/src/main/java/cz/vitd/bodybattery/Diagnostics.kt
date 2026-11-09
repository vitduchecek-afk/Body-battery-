package cz.vitd.bodybattery

import android.content.Context
import cz.vitd.bodybattery.core.Vo2Result

/** Builds the diagnostics text from the stored structured data, in the current locale. */
object Diagnostics {
    fun text(ctx: Context): String {
        val d = Repo.diagnosticsJson(ctx) ?: return ctx.getString(R.string.diag_empty)
        val sb = StringBuilder()
        val time = Texts.timeOrDateTime(ctx, d.optLong("time"))
        if (d.has("error")) {
            sb.append(ctx.getString(R.string.diag_error, time, d.optString("version"))).append('\n')
            sb.append(d.optString("error"))
            return sb.toString()
        }
        sb.append(ctx.getString(R.string.diag_version, d.optString("version"))).append("  ·  ").append(time).append("\n\n")

        sb.append("=== ").append(ctx.getString(R.string.diag_recognized)).append(" ===\n")
        sb.append(ctx.getString(R.string.diag_counts, d.optInt("hr"), d.optInt("stress"), d.optInt("hrv"))).append('\n')
        sb.append(ctx.getString(R.string.diag_sleep, d.optInt("sleeps"))).append('\n')

        Repo.lastResult(ctx)?.let { r ->
            sb.append("\n=== ").append(ctx.getString(R.string.diag_calc)).append(" ===\n")
            sb.append(ctx.getString(R.string.diag_calc_line, r.level, r.wakeLevel, r.charged, r.drained)).append('\n')
            r.notes.forEach { sb.append("• ").append(Texts.note(ctx, it)).append('\n') }
        }
        Repo.vo2(ctx)?.let { v ->
            val value = if (v is Vo2Result.Value) Texts.vo2Value(ctx, v.vo2) + " · " + Texts.vo2Source(ctx, v) else Texts.vo2Source(ctx, v)
            sb.append(ctx.getString(R.string.diag_vo2, value)).append('\n')
        }

        val tools = d.optJSONArray("tools")
        if (tools != null) {
            sb.append("\n=== ").append(ctx.getString(R.string.diag_tools, tools.length())).append(" ===\n")
            for (i in 0 until tools.length()) {
                val t = tools.optJSONArray(i) ?: continue
                sb.append("• ").append(t.optString(0)).append(": ").append(t.optString(1)).append('\n')
                t.optString(2).takeIf { it.isNotEmpty() }?.let { sb.append("   ").append(it).append('\n') }
            }
        }
        val calls = d.optJSONArray("calls")
        if (calls != null) {
            sb.append("\n=== ").append(ctx.getString(R.string.diag_calls, calls.length())).append(" ===\n")
            for (i in 0 until calls.length()) {
                val c = calls.optJSONArray(i) ?: continue
                sb.append("\n--- ").append(c.optString(0)).append(' ').append(c.optString(1))
                if (c.optBoolean(3)) sb.append(' ').append(ctx.getString(R.string.diag_call_error))
                sb.append('\n')
                val parts = c.optString(2).split('\u0000')
                if (parts.size == 3) {
                    sb.append(parts[0]).append('\n')
                    sb.append(ctx.getString(R.string.diag_truncated, parts[1].toIntOrNull() ?: 0)).append('\n')
                    sb.append(parts[2])
                } else sb.append(parts[0])
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
