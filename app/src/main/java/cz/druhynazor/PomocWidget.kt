package cz.druhynazor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Velké červené tlačítko Pomoc přímo na ploše (velké jako 4 ikony). Ťuknutí otevře Pomoc s odpočtem. */
class PomocWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val tap = PendingIntent.getActivity(ctx, 77,
            Intent(ctx, HelpActivity::class.java).setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(ctx.packageName, R.layout.widget_pomoc).apply { setOnClickPendingIntent(R.id.pomoc, tap) }
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        /** Nabídne dát tlačítko na plochu (telefon se zeptá okénkem Přidat). */
        fun pin(ctx: Context): Boolean {
            val m = ctx.getSystemService(AppWidgetManager::class.java) ?: return false
            if (!m.isRequestPinAppWidgetSupported) return false
            return try { m.requestPinAppWidget(ComponentName(ctx, PomocWidget::class.java), null, null) } catch (e: Exception) { false }
        }

        /** Už je tlačítko na ploše? */
        fun placed(ctx: Context): Boolean = try {
            ctx.getSystemService(AppWidgetManager::class.java)
                ?.getAppWidgetIds(ComponentName(ctx, PomocWidget::class.java))?.isNotEmpty() == true
        } catch (e: Exception) { false }
    }
}
