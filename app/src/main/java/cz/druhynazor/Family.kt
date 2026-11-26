package cz.druhynazor

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
    fun notify(context: Context, title: String, message: String, urgent: Boolean = true, reviewKey: String? = null,
               done: ((Boolean) -> Unit)? = null) {
        val prefs = Prefs(context)

        val question = reviewKey?.let { Verdicts.smsQuestion(context, it) } ?: ""
        val text = "Postaráno: $title\n${message.take(400)}" + if (question.isNotEmpty()) "\n\n$question" else ""
        sms(context, text, done = done)
    }

    /**
     * SMS všem z rodiny (kromě except). Do seznamu zpráv seniora se neukládá.
     * done (volitelně) dostane true, když telefon potvrdí odeslání všem, false při chybě
     * (není signál, chybí kredit, telefon SMS odmítl). Přijde na hlavním vlákně.
     */
    fun sms(context: Context, text: String, except: String? = null, done: ((Boolean) -> Unit)? = null) {
        val prefs = Prefs(context)
        val skip = except?.let { Prefs.normalize(it) }
        val targets = prefs.familyContacts.filter { skip == null || Prefs.normalize(it.phone) != skip }
        if (targets.isEmpty()) { done?.let { Handler(Looper.getMainLooper()).post { it(false) } }; return }
        var left = targets.size
        var allOk = true
        for (c in targets) {
            sendSms(context, c.phone, text) { ok ->
                if (!ok) allOk = false
                if (--left == 0) done?.invoke(allOk)
            }
        }
    }

    /**
     * SIM pro odesílání. Telefon se dvěma SIM a volbou „Pokaždé se zeptat“ nemá výchozí SIM pro SMS
     * a zpráva by tiše neodešla. Pak vezmeme první vloženou SIM.
     */
    @Suppress("DEPRECATION")
    fun smsManager(context: Context): SmsManager {
        val base = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else null) ?: SmsManager.getDefault()
        if (SubscriptionManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return try {
            val sub = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.firstOrNull()
            if (sub == null) base else {
                Log.i("DruhyNazor", "Výchozí SIM pro SMS není nastavená, posílám z první SIM")
                if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(sub.subscriptionId)
                else SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId)
            }
        } catch (e: SecurityException) { base }
    }

    private const val ACTION_SENT = "cz.druhynazor.SMS_ODESLANA"

    /** Proč poslední SMS neodešla, lidsky (pro zkoušku v Pro rodinu). */
    @Volatile var lastProblem: String? = null

    private fun problem(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> "telefon je v režimu letadlo nebo má vypnutou SIM (kód 2)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "telefon nemá signál (kód 4)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "telefon zprávu nepřijal (kód 3)"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "telefon poslal moc SMS najednou (kód 5)"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "operátor zprávu odmítl, nejspíš došel kredit na SIM kartě. Dobijte ho a zkuste to znovu (kód 1)"
        else -> "telefon zprávu odmítl (kód $code)"
    }

    /** Jen číslice a + na začátku. Kontakty ze Samsungu mají v čísle i neviditelné znaky. */
    fun cleanNumber(phone: String): String {
        val t = phone.trim()
        val digits = t.filter { it in '0'..'9' }
        return if (t.contains("+")) "+$digits" else digits
    }
    private var counter = 0

    /** Jedna SMS. result (volitelně): true = telefon potvrdil odeslání všech částí. */
    fun sendSms(context: Context, phone: String, text: String, result: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        try {
            val sm = smsManager(app)
            val number = cleanNumber(phone)
            if (number.filter { it.isDigit() }.length < 9) {
                lastProblem = "číslo „$phone“ není celé telefonní číslo"
                Log.w("DruhyNazor", "SMS rodině: špatné číslo")
                main.post { result?.invoke(false) }
                return
            }
            val parts = sm.divideMessage(text)
            val id = "${System.currentTimeMillis()}-${counter++}"
            var left = parts.size
            var ok = true
            var finished = false
            lateinit var receiver: BroadcastReceiver
            fun finish(success: Boolean) {
                if (finished) return
                finished = true
                try { app.unregisterReceiver(receiver) } catch (_: Exception) { }
                Log.i("DruhyNazor", if (success) "SMS rodině odeslána (${parts.size} částí)" else "SMS rodině NEODEŠLA")
                if (success) lastProblem = null else if (lastProblem == null) lastProblem = "telefon do 2 minut nepotvrdil odeslání"
                result?.invoke(success)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getStringExtra("id") != id) return
                    if (resultCode != Activity.RESULT_OK) {
                        ok = false
                        lastProblem = problem(resultCode)
                        Log.w("DruhyNazor", "Část SMS neodešla, kód $resultCode")
                    }
                    if (--left <= 0) finish(ok)
                }
            }
            val filter = IntentFilter(ACTION_SENT)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            val sent = ArrayList<PendingIntent>()
            for (n in parts.indices) {
                sent += PendingIntent.getBroadcast(app, (id.hashCode() * 31 + n),
                    Intent(ACTION_SENT).setPackage(app.packageName).putExtra("id", id),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            }
            lastProblem = null
            sm.sendMultipartTextMessage(number, null, parts, sent, null)
            // Když telefon do 2 minut nic neřekne, bereme to jako neodeslané
            main.postDelayed({ finish(false) }, 120_000)
        } catch (e: SecurityException) {
            lastProblem = "Postaráno nemá povolení posílat SMS"
            Log.w("DruhyNazor", "SMS rodině: chybí povolení", e)
            main.post { result?.invoke(false) }
        } catch (e: Exception) {
            lastProblem = "chyba v telefonu: ${e.javaClass.simpleName}"
            Log.w("DruhyNazor", "SMS rodině se nepodařilo odeslat", e)
            main.post { result?.invoke(false) }
        }
    }
}
