package cz.vitd.bodybattery

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BatteryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        Repo.schedule(context)
        Repo.refreshSoon(context)
    }

    override fun onEnabled(context: Context) {
        Repo.schedule(context)
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            render(context, manager, Repo.widgetIds(context))
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val result = Repo.lastResult(context)
            val loggedIn = Repo.client(context).isLoggedIn()
            val caption = when {
                !loggedIn -> "připojit"
                result == null -> "načítám"
                else -> Instant.ofEpochSecond(result.computedAt).atZone(ZoneId.systemDefault()).format(TIME)
            }
            val bmp = RingRenderer.render(360, result?.level, caption)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget)
                views.setImageViewBitmap(R.id.widget_ring, bmp)
                views.setContentDescription(R.id.widget_ring, "Body battery ${result?.level ?: "neznámá"}")
                views.setOnClickPendingIntent(R.id.widget_root, open)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
