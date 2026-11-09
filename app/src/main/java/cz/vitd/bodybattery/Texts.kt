package cz.vitd.bodybattery

import android.content.Context
import android.text.format.DateFormat
import cz.vitd.bodybattery.core.BatteryModel
import cz.vitd.bodybattery.core.Note
import cz.vitd.bodybattery.core.Problem
import cz.vitd.bodybattery.core.Vo2Missing
import cz.vitd.bodybattery.core.Vo2Result
import cz.vitd.bodybattery.core.Vo2Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** All user-visible text is produced here from string resources, in the current locale. */
object Texts {
    private fun locale(ctx: Context) = ctx.resources.configuration.locales[0]

    fun time(ctx: Context, epoch: Long): String =
        Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale(ctx)))

    /** Time only when [epoch] is today, otherwise short date + time. */
    fun timeOrDateTime(ctx: Context, epoch: Long): String {
        val z = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
        return if (z.toLocalDate() == LocalDate.now()) time(ctx, epoch)
        else z.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale(ctx)))
    }

    fun clock(ctx: Context, hour: Int, minute: Int): String {
        val pattern = DateFormat.getBestDateTimePattern(locale(ctx), if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm")
        return java.time.LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, locale(ctx)))
    }

    /** Pattern for chart hour labels ("14", "2 PM", "14時" …). */
    fun hourPattern(ctx: Context): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale(ctx), if (DateFormat.is24HourFormat(ctx)) "H" else "h"), locale(ctx))

    fun levelLabel(ctx: Context, level: Int): String = ctx.getString(
        when {
            level >= 76 -> R.string.level_high
            level >= 51 -> R.string.level_medium
            level >= 26 -> R.string.level_low
            else -> R.string.level_very_low
        }
    )

    fun note(ctx: Context, n: Note): String = when (n) {
        Note.WAKE_UNKNOWN -> ctx.getString(R.string.note_wake_unknown)
        Note.NO_SLEEP_DATA -> ctx.getString(R.string.note_no_sleep)
        Note.LOW_DATA -> ctx.getString(R.string.note_low_data)
        Note.START_REPLAYED -> ctx.getString(R.string.note_start_replayed)
        Note.START_DEFAULT -> ctx.getString(R.string.note_start_default, BatteryModel.DEFAULT_START.toInt())
    }

    /** Error codes stored by [Repo]: a [Problem] name or one of the app-level codes below. */
    const val ERR_NETWORK = "NETWORK"
    const val ERR_LOGIN_START = "LOGIN_START"
    const val ERR_LOGIN_TIMEOUT = "LOGIN_TIMEOUT"
    const val ERR_UNKNOWN = "UNKNOWN"

    fun error(ctx: Context, code: String, detail: String?): String {
        val base = when (code) {
            Problem.NOT_CONNECTED.name -> ctx.getString(R.string.err_not_connected)
            Problem.SESSION_EXPIRED.name -> ctx.getString(R.string.err_session_expired)
            Problem.LOGIN_NONE.name -> ctx.getString(R.string.err_login_none)
            Problem.LOGIN_EXPIRED.name -> ctx.getString(R.string.err_login_expired)
            Problem.LOGIN_FAILED.name -> ctx.getString(R.string.err_login_failed)
            Problem.LOGIN_USED.name -> ctx.getString(R.string.err_login_used)
            Problem.LOGIN_UNEXPECTED.name -> ctx.getString(R.string.err_login_unexpected)
            ERR_NETWORK -> ctx.getString(R.string.err_network)
            ERR_LOGIN_START -> ctx.getString(R.string.err_login_start)
            ERR_LOGIN_TIMEOUT -> ctx.getString(R.string.err_login_timeout)
            else -> ctx.getString(R.string.err_server)
        }
        return if (detail.isNullOrBlank()) base else ctx.getString(R.string.fmt_detail, base, detail.take(160))
    }

    fun vo2Value(ctx: Context, v: Double): String = ctx.getString(R.string.fmt_vo2, v)

    fun vo2Source(ctx: Context, r: Vo2Result): String = when (r) {
        is Vo2Result.Value -> ctx.getString(
            when (r.source) {
                Vo2Source.COROS -> R.string.vo2_source_coros
                Vo2Source.ESTIMATE_RUNS -> R.string.vo2_source_runs
                Vo2Source.ESTIMATE_RESTING_HR -> R.string.vo2_source_rhr
            }
        )
        is Vo2Result.Insufficient -> ctx.getString(R.string.vo2_no_data)
    }

    fun vo2Missing(ctx: Context, missing: Set<Vo2Missing>): String = missing.sortedBy { it.ordinal }.joinToString("\n") {
        ctx.getString(
            when (it) {
                Vo2Missing.RUNS -> R.string.vo2_missing_runs
                Vo2Missing.RESTING_HR -> R.string.vo2_missing_rhr
                Vo2Missing.MAX_HR -> R.string.vo2_missing_maxhr
            }
        )
    }

    fun vo2Explanation(ctx: Context, r: Vo2Result?): String = when {
        r is Vo2Result.Value && r.source == Vo2Source.COROS -> ctx.getString(R.string.vo2_explain_coros)
        r is Vo2Result.Value && r.source == Vo2Source.ESTIMATE_RUNS -> ctx.getString(R.string.vo2_explain_runs)
        r is Vo2Result.Value -> ctx.getString(R.string.vo2_explain_rhr)
        r is Vo2Result.Insufficient -> ctx.getString(R.string.vo2_explain_missing) + "\n\n" + vo2Missing(ctx, r.missing)
        else -> ctx.getString(R.string.vo2_explain_missing)
    }
}
