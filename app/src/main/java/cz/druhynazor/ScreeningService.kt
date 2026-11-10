package cz.druhynazor

import android.content.Intent
import android.graphics.Color
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Android sem pošle každý hovor z čísla, které NENÍ v kontaktech, ještě než zazvoní.
 * Zkušební verze hovory neblokuje, jen varuje a zapamatuje si číslo.
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart ?: ""
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING

        if (incoming) {
            val prefs = Prefs(this)
            prefs.lastUnknownNumber = number
            prefs.lastUnknownTime = System.currentTimeMillis()
            Log.i("DruhyNazor", "Příchozí hovor z neznámého čísla: $number")

            if (prefs.isTestScamNumber(number)) {
                val tap = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Notify.show(this, Notify.ID_CALL_WARN, Notify.CH_WARN,
                    "⚠️ POZOR, PODVODNÍK",
                    "Volá číslo nahlášené jako podvod: $number. Nejlépe to nezvedejte.", tap, Color.RED)
                Family.notify(this, "${prefs.seniorName}: volá podvodník",
                    "Volá nahlášené podvodné číslo $number.")
            }
        }

        // Hovor necháme normálně zazvonit (v testu nic neblokujeme)
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build())

        // Hlídání konce hovoru musí běžet
        GuardService.start(this)
    }
}
