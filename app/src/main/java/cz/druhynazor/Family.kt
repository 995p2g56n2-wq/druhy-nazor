package cz.druhynazor

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Upozornění rodině.
 * Hlavní cesta: obyčejná SMS z telefonu seniora vybraným lidem z rodiny. Rodina nemusí nic instalovat
 * a na podezřelou zprávu odpoví ANO nebo NE.
 * Zkušební cesta navíc: aplikace ntfy s tlačítky (dá se vypnout).
 */
object Family {

    /**
     * @param reviewKey když je vyplněný, rodina má zprávu posoudit (SMS s ANO / NE, v ntfy tlačítka).
     */
    fun notify(context: Context, title: String, message: String, urgent: Boolean = true, reviewKey: String? = null) {
        val prefs = Prefs(context)

        val question = reviewKey?.let { Verdicts.smsQuestion(context, it) } ?: ""
        val text = "Postaráno: $title\n${message.take(400)}" + if (question.isNotEmpty()) "\n\n$question" else ""
        sms(context, text)

        if (prefs.ntfyEnabled) {
            val ask = if (reviewKey != null)
                "\n\nPodívejte se a ťukněte na tlačítko níže. ${prefs.seniorName} hned uvidí, jak jste rozhodli." else ""
            ntfy(context, title, message + ask, urgent, reviewKey?.let { Verdicts.actions(context, it) })
        }
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

    fun ntfy(context: Context, title: String, message: String, urgent: Boolean, actions: JSONArray? = null) {
        val channel = Prefs(context).familyChannel
        thread(name = "family-notify") {
            try {
                val body = JSONObject()
                    .put("topic", channel)
                    .put("title", title)
                    .put("message", message)
                    .put("priority", if (urgent) 5 else 3)
                    .put("tags", JSONArray().put(if (urgent) "rotating_light" else "warning"))
                    .apply { if (actions != null) put("actions", actions) }
                    .toString()
                val conn = (URL("https://ntfy.sh/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Log.i("DruhyNazor", "Upozornění do ntfy odesláno, odpověď $code")
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Upozornění do ntfy se nepodařilo odeslat", e)
            }
        }
    }
}
