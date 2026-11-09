package cz.vitd.bodybattery

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cz.vitd.bodybattery.core.BatteryResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Runs on an emulator in CI (see .github/workflows/build.yml):
 *  - the daily alarm really fires at the configured minute and posts the notification
 *  - no notification without data, stale values are labelled with their time
 *  - notification / widget texts follow the system language
 */
@RunWith(AndroidJUnit4::class)
class DailyNotificationTest {
    @get:Rule
    val permission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun result(level: Int, computedAt: Long) = BatteryResult(
        level = level, wakeLevel = 80, charged = 40, drained = 8,
        sleepStart = computedAt - 12 * 3600, wakeTime = computedAt - 4 * 3600, computedAt = computedAt,
        points = emptyList(), notes = emptyList(), detailed = true,
    )

    private fun now() = System.currentTimeMillis() / 1000

    private fun localized(tag: String): Context {
        val conf = Configuration(ctx.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(tag))
        return ctx.createConfigurationContext(conf)
    }

    @Before
    fun setUp() {
        Repo.client(ctx).logout() // no network refresh in tests: the last known value is used
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        Repo.setNotificationEnabled(ctx, false)
        DailyNotifier.schedule(ctx)
        nm.cancelAll()
    }

    @Test
    fun alarmPostsNotificationAtConfiguredTime() {
        Repo.storeResult(ctx, result(72, now()))
        val start = ZonedDateTime.now()
        // next full minute, at least 30 s ahead
        val trigger = start.plusSeconds(90).withSecond(0).withNano(0)
        Repo.setNotificationTime(ctx, trigger.hour, trigger.minute)
        Repo.setNotificationEnabled(ctx, true)
        DailyNotifier.schedule(ctx)
        Log.i("DailyNotificationTest", "scheduled for $trigger (now $start)")

        val deadline = SystemClock.elapsedRealtime() + 5 * 60_000
        var posted: Notification? = null
        while (posted == null && SystemClock.elapsedRealtime() < deadline) {
            posted = nm.activeNotifications.firstOrNull { it.id == DailyNotifier.NOTIFICATION_ID }?.notification
            if (posted == null) SystemClock.sleep(1000)
        }
        val arrived = ZonedDateTime.now()
        Log.i("DailyNotificationTest", "notification arrived at $arrived, ${java.time.Duration.between(trigger, arrived).seconds} s after the trigger time")
        assertNotNull("notification not posted within 5 minutes", posted)
        assertTrue("arrived before the configured time", !arrived.isBefore(trigger.minusSeconds(2)))
        val text = posted!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(ctx.getString(R.string.notif_today, 72, Texts.levelLabel(ctx, 72)), text)
        assertEquals(ctx.getString(R.string.charge_term), posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull("tapping must open the app", posted.contentIntent)
    }

    @Test
    fun noNotificationWithoutAnyValue() {
        Repo.store(ctx).put("result", null)
        assertNull(DailyNotifier.content(ctx))
        assertFalse(DailyNotifier.post(ctx))
        val old = result(50, now() - 48 * 3600)
        Repo.storeResult(ctx, old)
        assertNull("a two days old value must not be shown", DailyNotifier.content(ctx))
    }

    @Test
    fun staleValueIsLabelledWithItsTime() {
        val at = now() - 3 * 3600
        Repo.storeResult(ctx, result(40, at))
        val text = DailyNotifier.content(ctx)!!
        assertEquals(ctx.getString(R.string.notif_last, Texts.timeOrDateTime(ctx, at), 40, Texts.levelLabel(ctx, 40)), text)
    }

    @Test
    fun textsFollowSystemLanguage() {
        Repo.storeResult(ctx, result(72, now()))
        val expected = mapOf("en" to "Today", "cs" to "Dnes", "de" to "Heute", "fr" to "Aujourd", "ja" to "今日", "zh-CN" to "今天", "hi" to "आज")
        for ((tag, word) in expected) {
            val c = localized(tag)
            val text = DailyNotifier.content(c)!!
            assertTrue("$tag: '$text' should contain '$word'", text.contains(word))
        }
        assertNotEquals(localized("en").getString(R.string.widget_connect), localized("ru").getString(R.string.widget_connect))
        assertEquals("Charge for COROS", localized("ja").getString(R.string.app_name))
        assertEquals("Nabití", localized("cs").getString(R.string.charge_term))
    }
}
