package cz.druhynazor

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Čte oznámení z WhatsAppu, Messengeru a dalších chatů a kontroluje jejich text.
 * Hlídá i hovory přes WhatsApp z neznámých čísel (otázka ANO / NE po hovoru).
 */
class ChatWatcher : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val seen = LinkedHashSet<Int>()

    private var callKey: String? = null
    private var caller = ""
    private var callStart = 0L
    private var callApp = ""

    private val longCall = Runnable {
        val prefs = Prefs(this)
        val minutes = ((System.currentTimeMillis() - callStart) / 60_000).coerceAtLeast(1)
        Family.notify(this, "${prefs.seniorName} mluví $minutes min s neznámým číslem ($callApp)",
            "Číslo: $caller. Až zavěsí, zkuste jí zavolat.", urgent = false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""

        if (n.category == Notification.CATEGORY_CALL) {
            onCall(sbn, app, title)
            return
        }

        val text = latestText(extras)
        if (text.isBlank()) return

        // Stejné oznámení chodí opakovaně (aktualizace), kontrolujeme jen jednou
        val fingerprint = (sbn.packageName + title + text).hashCode()
        if (!seen.add(fingerprint)) return
        if (seen.size > 200) seen.remove(seen.first())

        val result = ScamCheck.check(text)
        if (result.level == ScamCheck.Level.OK) return

        val prefs = Prefs(this)
        val tap = Intent(this, MessageActivity::class.java).apply {
            putExtra(MessageActivity.EXTRA_ADDRESS, "$title ($app)")
            putExtra(MessageActivity.EXTRA_BODY, text)
            putExtra(MessageActivity.EXTRA_CHAT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val id = 5000 + (fingerprint and 0xFFFF)
        if (result.level == ScamCheck.Level.SCAM) {
            Notify.show(this, id, Notify.CH_WARN, "⚠️ Pozor, podvod na $app",
                "Od: $title. Na nic neklikejte a nic neposílejte. ${prefs.familyName} už o tom ví.", tap, Color.RED)
            Family.notify(this, "${prefs.seniorName}: podvodná zpráva na $app",
                "Od: $title\n${result.reason}\n\nText: ${text.take(300)}")
        } else {
            Notify.show(this, id, Notify.CH_WARN, "⚠️ Podezřelá zpráva na $app",
                "Od: $title. Zatím nic nedělejte, ptáme se: ${prefs.familyName}.", tap, Color.rgb(0xA8, 0x5D, 0x00))
            Family.notify(this, "${prefs.seniorName}: podezřelá zpráva na $app, je v pořádku?",
                "Od: $title\n${result.reason}\n\nText: ${text.take(300)}", urgent = false)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != callKey) return
        handler.removeCallbacks(longCall)
        val lasted = System.currentTimeMillis() - callStart
        val number = caller
        val app = callApp
        callKey = null
        // Krátké oznámení = nezvednutý hovor, otázku nedáváme
        if (lasted > 20_000) {
            QuestionActivity.launch(this, "$number ($app)")
        }
    }

    private fun onCall(sbn: StatusBarNotification, app: String, title: String) {
        if (callKey != null) {
            // Během hovoru se oznámení mění (zvoní → probíhá), sledujeme nové
            if (title.isNotBlank() && looksLikeNumber(title)) callKey = sbn.key
            return
        }
        // Volá-li někdo z kontaktů, WhatsApp ukáže jméno. Číslo = neznámý volající
        if (!looksLikeNumber(title)) return
        val prefs = Prefs(this)
        callKey = sbn.key
        caller = title
        callApp = app
        callStart = System.currentTimeMillis()
        handler.postDelayed(longCall, prefs.longCallMinutes * 60_000L)
        Log.i("DruhyNazor", "Hovor přes $app z neznámého čísla $title")

        if (prefs.isTestScamNumber(title)) {
            val tap = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Notify.show(this, Notify.ID_CALL_WARN, Notify.CH_WARN, "⚠️ POZOR, PODVODNÍK",
                "Přes $app volá číslo nahlášené jako podvod: $title.", tap, Color.RED)
            Family.notify(this, "${prefs.seniorName}: přes $app volá podvodník", "Číslo: $title")
        }
    }

    private fun latestText(extras: Bundle): String {
        // Konverzace (WhatsApp, Messenger): vezmeme poslední zprávu
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val last = messages.last() as? Bundle
            val t = last?.getCharSequence("text")?.toString()
            if (!t.isNullOrBlank()) return t
        }
        return (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
    }

    private fun looksLikeNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 6 && digits >= s.count { it.isLetter() } * 2
    }

    companion object {
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger",
            "org.telegram.messenger" to "Telegram",
            "com.viber.voip" to "Viber",
            "com.instagram.android" to "Instagram"
        )

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, ChatWatcher::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
