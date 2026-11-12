package cz.druhynazor

import android.content.Intent
import android.graphics.Color
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Android sem pošle každý hovor z čísla, které NENÍ v kontaktech:
 * příchozí ještě než zazvoní, odchozí (zpětné volání) ještě než se spojí.
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart ?: ""
        val prefs = Prefs(this)
        var block = false

        when (details.callDirection) {
            Call.Details.DIRECTION_INCOMING -> {
                prefs.lastUnknownNumber = number
                prefs.lastUnknownTime = System.currentTimeMillis()
                prefs.ringingScam = prefs.isTestScamNumber(number)
                if (Prefs.isForeign(number)) prefs.rememberForeignCaller(number)
                Log.i("DruhyNazor", "Příchozí hovor z neznámého čísla: $number")

                if (prefs.isTestScamNumber(number)) {
                    if (prefs.blockScamCalls) {
                        block = true
                        prefs.lastUnknownTime = 0L
                        prefs.ringingScam = false
                        Family.notify(this, "${prefs.seniorName}: zablokován hovor od podvodníka",
                            "Číslo $number. Telefon vůbec nezazvonil a hovor se neukáže ani ve zmeškaných.", urgent = false)
                    } else {
                        val tap = Intent(this, ScamCallActivity::class.java)
                            .putExtra(ScamCallActivity.EXTRA_NUMBER, number)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        Notify.show(this, Notify.ID_CALL_WARN, Notify.CH_WARN,
                            "⚠️ POZOR, PODVODNÍK",
                            "Volá číslo nahlášené jako podvod: $number. Neberte to.", tap, Color.RED)
                        ScamCallActivity.show(this, number)
                        Family.notify(this, "${prefs.seniorName}: volá podvodník",
                            "Volá nahlášené podvodné číslo $number.")
                    }
                }
            }

            Call.Details.DIRECTION_OUTGOING -> {
                val reason = when {
                    prefs.isTestScamNumber(number) -> ScamCallActivity.REASON_SCAM
                    Prefs.isForeign(number) && prefs.foreignCallerRecently(number) -> ScamCallActivity.REASON_FOREIGN
                    else -> null
                }
                if (reason != null) {
                    Log.i("DruhyNazor", "Volá se zpátky rizikové číslo: $number")
                    ScamCallActivity.show(this, number, outgoing = reason)
                    Family.notify(this,
                        if (reason == ScamCallActivity.REASON_SCAM) "${prefs.seniorName} volá zpátky podvodníkovi!"
                        else "${prefs.seniorName} volá zpátky na zahraniční číslo, které ji prozvonilo",
                        "Číslo $number. Zavolejte jí hned, ať zavěsí.")
                }
            }
        }

        // Zablokovaný hovor nezazvoní a neukáže se ani ve zmeškaných, aby nebylo komu volat zpátky
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(block)
            .setRejectCall(block)
            .setSkipCallLog(block)
            .setSkipNotification(block)
            .build())

        GuardService.start(this)
    }
}
