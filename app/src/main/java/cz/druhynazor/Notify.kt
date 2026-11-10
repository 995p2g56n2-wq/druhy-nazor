package cz.druhynazor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color

object Notify {
    const val CH_WARN = "varovani"
    const val CH_MSG = "zpravy"
    const val CH_GUARD = "hlidani_bez_cisla"
    const val CH_QUESTION = "otazka"

    const val ID_GUARD = 1
    const val ID_QUESTION = 2
    const val ID_CALL_WARN = 3

    fun setup(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WARN, "Varování před podvodem", NotificationManager.IMPORTANCE_HIGH).apply {
            enableLights(true); lightColor = Color.RED; enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Zprávy", NotificationManager.IMPORTANCE_HIGH))
        nm.deleteNotificationChannel("hlidani")
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Hlídání hovorů", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUESTION, "Otázka po hovoru", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
    }

    fun pending(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun show(context: Context, id: Int, channel: String, title: String, text: String, tap: Intent, color: Int? = null) {
        setup(context)
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending(context, tap, id))
            .apply { if (color != null) { setColor(color); setColorized(false) } }
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) { }
    }
}
