package cz.druhynazor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Rodina potvrzuje podezřelé zprávy. Upozornění v ntfy má tlačítka „Je to v pořádku“ a „Je to podvod“.
 * Tlačítko pošle odpověď do kanálu „…-odpoved“, telefon seniora ji přečte a zprávu označí.
 * Každá zpráva má vlastní náhodný klíč, takže cizí odpověď bez něj nic nezmění.
 */
object Verdicts {
    enum class Verdict { OK, SCAM }

    private const val NTFY = "https://ntfy.sh/"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("verdikty", Context.MODE_PRIVATE)

    fun replyChannel(ctx: Context) = Prefs(ctx).familyChannel + "-odpoved"

    fun keyForSms(id: Long) = "s$id"
    fun keyForText(from: String, text: String) = "c" + (from + text).hashCode().toUInt().toString(16)

    fun get(ctx: Context, key: String): Verdict? = when (prefs(ctx).getString("v_$key", null)) {
        "ok" -> Verdict.OK
        "scam" -> Verdict.SCAM
        else -> null
    }

    /** Tlačítka do upozornění pro rodinu. */
    fun actions(ctx: Context, key: String): JSONArray {
        val p = prefs(ctx)
        val token = p.getString("t_$key", null) ?: (1..10).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }
            .joinToString("").also { p.edit().putString("t_$key", it).apply() }
        val url = NTFY + replyChannel(ctx)
        fun btn(label: String, verdict: String) = JSONObject()
            .put("action", "http").put("label", label).put("url", url)
            .put("method", "POST").put("body", "$verdict|$key|$token").put("clear", true)
        return JSONArray().put(btn("✅ Je to v pořádku", "ok")).put(btn("⛔ Je to podvod", "scam"))
    }

    /** Zpracuje jednu odpověď z kanálu. Vrací true, pokud se něco změnilo. */
    private fun apply(ctx: Context, message: String): Boolean {
        val parts = message.trim().split('|')
        if (parts.size != 3) return false
        val (verdict, key, token) = parts
        val p = prefs(ctx)
        if (verdict != "ok" && verdict != "scam") return false
        if (p.getString("t_$key", null) != token) return false
        if (p.getString("v_$key", null) == verdict) return false
        p.edit().putString("v_$key", verdict).apply()

        val prefsMain = Prefs(ctx)
        val ok = verdict == "ok"
        val family = prefsMain.familyName
        val tap = Intent(ctx, DruhyNazorActivity::class.java)
            .putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Notify.show(ctx, 7000 + (key.hashCode() and 0xFFF), if (ok) Notify.CH_MSG else Notify.CH_WARN,
            if (ok) "✅ $family to zkontroloval" else "⛔ $family potvrdil podvod",
            if (ok) "Zpráva je v pořádku, můžete ji otevřít." else "Na nic neklikejte, nic neplaťte a na zprávu neodpovídejte.",
            tap, if (ok) Ui.OK else Color.RED)
        Family.notify(ctx, "Hotovo",
            if (ok) "${prefsMain.seniorName} uvidí, že je zpráva v pořádku, a odkaz půjde otevřít."
            else "${prefsMain.seniorName} uvidí, že je to potvrzený podvod.", urgent = false)
        return true
    }

    private fun handleLine(ctx: Context, line: String): Boolean = try {
        val o = JSONObject(line)
        if (o.optString("event") != "message") false else {
            prefs(ctx).edit().putString("since_id", o.optString("id")).apply()
            apply(ctx, o.optString("message"))
        }
    } catch (e: Exception) { false }

    private fun sinceParam(ctx: Context): String {
        // ntfy umí pokračovat od posledního přečteného ID zprávy; napoprvé posledních 12 hodin
        val id = prefs(ctx).getString("since_id", null)
        return if (!id.isNullOrBlank()) URLEncoder.encode(id, "UTF-8") else "12h"
    }

    private fun open(ctx: Context, poll: Boolean): HttpURLConnection {
        val topic = URLEncoder.encode(replyChannel(ctx), "UTF-8")
        val url = NTFY + topic + "/json?since=" + sinceParam(ctx) + if (poll) "&poll=1" else ""
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = if (poll) 15_000 else 120_000 // ntfy posílá keepalive zhruba každých 45 s
        }
    }

    /** Jednorázově stáhne nové odpovědi (při otevření zprávy). Hotovo = zavolá onDone na hlavním vlákně. */
    fun pollNow(ctx: Context, onDone: (changed: Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread(name = "verdikty-poll") {
            var changed = false
            try {
                val c = open(app, poll = true)
                c.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (handleLine(app, it)) changed = true } }
                c.disconnect()
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Odpovědi rodiny se nepodařilo načíst", e)
            }
            main.post { onDone(changed) }
        }
    }

    @Volatile private var listening = false

    /** Trvale poslouchá odpovědi rodiny. Běží, dokud běží hlídání. */
    fun startListening(ctx: Context) {
        if (listening) return
        listening = true
        val app = ctx.applicationContext
        thread(name = "verdikty", isDaemon = true) {
            var wait = 5_000L
            while (listening) {
                try {
                    val c = open(app, poll = false)
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) { if (!listening) break; handleLine(app, l); wait = 5_000L }
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    Log.i("DruhyNazor", "Spojení pro odpovědi rodiny přerušeno, zkusím znovu")
                }
                if (!listening) break
                try { Thread.sleep(wait) } catch (_: InterruptedException) { }
                wait = (wait * 2).coerceAtMost(300_000L)
            }
        }
    }

    fun stopListening() { listening = false }
}
