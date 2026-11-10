package cz.druhynazor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Telephony

/** Přijatá SMS: uložit, zkontrolovat, upozornit babičku i rodinu. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // Dlouhá SMS přichází po částech, poskládáme ji podle odesílatele
        val grouped = parts.groupBy { it.displayOriginatingAddress ?: "" }
        for ((address, msgs) in grouped) {
            val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
            val sentAt = msgs.first().timestampMillis
            val id = SmsStore.saveIncoming(context, address, body, sentAt)
            handle(context, address, body, id)
        }
    }

    private fun handle(context: Context, address: String, body: String, id: Long?) {
        val prefs = Prefs(context)
        val result = ScamCheck.check(body)
        val open = Intent(context, MessageActivity::class.java).apply {
            putExtra(MessageActivity.EXTRA_ID, id ?: -1L)
            putExtra(MessageActivity.EXTRA_ADDRESS, address)
            putExtra(MessageActivity.EXTRA_BODY, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val notifId = 1000 + ((id ?: System.currentTimeMillis()) % 100_000).toInt()

        when (result.level) {
            ScamCheck.Level.SCAM -> {
                Notify.show(context, notifId, Notify.CH_WARN, "⚠️ Pozor, podvod",
                    "Od: $address. Na nic neklikejte a nic neplaťte. ${prefs.familyName} už o tom ví.", open, Color.RED)
                Family.notify(context, "${prefs.seniorName}: podvodná SMS",
                    "Od: $address\n${result.reason}\nOdkaz je zablokovaný.\n\nText: ${body.take(300)}")
            }
            ScamCheck.Level.WARN -> {
                Notify.show(context, notifId, Notify.CH_WARN, "⚠️ Podezřelá zpráva",
                    "Od: $address. Zatím nic nedělejte, ptáme se: ${prefs.familyName}.", open, Color.rgb(0xA8, 0x5D, 0x00))
                Family.notify(context, "${prefs.seniorName}: podezřelá SMS, je v pořádku?",
                    "Od: $address\n${result.reason}\n\nText: ${body.take(300)}", urgent = false)
            }
            ScamCheck.Level.OK -> {
                Notify.show(context, notifId, Notify.CH_MSG, address, body, open)
            }
        }
    }
}
