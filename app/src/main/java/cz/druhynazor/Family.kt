package cz.druhynazor

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Upozornění rodině.
 * Obyčejná SMS z telefonu seniora vybraným lidem z rodiny. Rodina nemusí nic instalovat
 * a na podezřelou zprávu odpoví ANO nebo NE.
 */
object Family {

    /**
     * @param reviewKey když je vyplněný, rodina má zprávu posoudit (SMS s ANO / NE).
     */
    fun notify(context: Context, title: String, message: String, urgent: Boolean = true, reviewKey: String? = null) {
        val prefs = Prefs(context)

        val question = reviewKey?.let { Verdicts.smsQuestion(context, it) } ?: ""
        val text = "Postaráno: $title\n${message.take(400)}" + if (question.isNotEmpty()) "\n\n$question" else ""
        sms(context, text)
    }

    /** SMS všem z rodiny (kromě except). Do seznamu zpráv seniora se neukládá. */
    fun sms(context: Context, text: String, except: String? = null) {
        val prefs = Prefs(context)
        val skip = except?.let { Prefs.normalize(it) }
        for (c in prefs.familyContacts) {
            if (skip != null && Prefs.normalize(c.phone) == skip) continue
            sendSms(context, c.phone, text)
        }
    }

    fun sendSms(context: Context, phone: String, text: String) {
        try {
            @Suppress("DEPRECATION")
            val sm = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            sm.sendMultipartTextMessage(phone, null, sm.divideMessage(text), null, null)
            Log.i("DruhyNazor", "SMS rodině odeslána")
        } catch (e: Exception) {
            Log.w("DruhyNazor", "SMS rodině se nepodařilo odeslat", e)
        }
    }
}
