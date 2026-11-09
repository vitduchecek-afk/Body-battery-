package cz.vitd.bodybattery

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Daily "Charge" notification.
 *
 * Scheduling: one AlarmManager alarm per day (exact while-idle if the user allowed exact
 * alarms, otherwise the Doze-friendly inexact while-idle variant, usually within minutes).
 * The alarm is re-armed after it fires, after reboot, time / time zone changes and app updates.
 * When it fires, data is refreshed first (≤ 25 s); if that fails the last known value is shown
 * with its time. Without any known value no notification is posted.
 */
object DailyNotifier {
    const val CHANNEL_ID = "daily_charge"
    const val NOTIFICATION_ID = 1001
    const val ACTION_FIRE = "cz.vitd.bodybattery.DAILY_CHARGE"
    private const val REFRESH_TIMEOUT_MS = 25_000L
    /** A value older than this is not shown at all. */
    private const val MAX_AGE_SEC = 36 * 3600L
    /** A value at most this old counts as "today/now". */
    private const val FRESH_SEC = 45 * 60L

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = ctx.getString(R.string.notif_channel_desc)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch) // also updates the name after a language change
    }

    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val today = now.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, DailyAlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** (Re)arms or cancels the alarm according to the settings. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        if (!Repo.notificationEnabled(ctx)) return
        val (h, m) = Repo.notificationTime(ctx)
        val at = nextTrigger(ZonedDateTime.now(), h, m).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Text for the notification, or null when there is no trustworthy value to show. */
    fun content(ctx: Context, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val r = Repo.lastResult(ctx) ?: return null
        val age = nowSec - r.computedAt
        if (age > MAX_AGE_SEC) return null
        val label = Texts.levelLabel(ctx, r.level)
        val sameDay = java.time.Instant.ofEpochSecond(r.computedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        return if (age <= FRESH_SEC && sameDay) ctx.getString(R.string.notif_today, r.level, label)
        else ctx.getString(R.string.notif_last, Texts.timeOrDateTime(ctx, r.computedAt), r.level, label)
    }

    /** Posts the notification. Returns false when nothing was posted. */
    fun post(ctx: Context): Boolean {
        if (!canPost(ctx)) return false
        val text = content(ctx) ?: return false
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setColor(RingRenderer.colorFor(Repo.lastResult(ctx)?.level ?: 100))
            .setContentTitle(ctx.getString(R.string.charge_term))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Called when the alarm fires: refresh (bounded), post, re-arm. */
    suspend fun fire(ctx: Context) {
        try {
            if (Repo.client(ctx).isLoggedIn()) withTimeoutOrNull(REFRESH_TIMEOUT_MS) { Repo.refresh(ctx) }
            post(ctx)
        } finally {
            schedule(ctx)
        }
    }
}

class DailyAlarmReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyNotifier.ACTION_FIRE) return
        val app = context.applicationContext
        val result = goAsync()
        scope.launch {
            try {
                DailyNotifier.fire(app)
            } finally {
                result.finish()
            }
        }
    }
}

/** Re-arms the alarm and refreshes language-dependent UI after system events. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_LOCALE_CHANGED -> {
                DailyNotifier.ensureChannel(app)
                BatteryWidget.updateAll(app)
            }
            else -> DailyNotifier.schedule(app) // boot, time / time zone change, app update
        }
    }
}
