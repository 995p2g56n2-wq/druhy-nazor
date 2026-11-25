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

    /** Klíč zkušební otázky „Vyzkoušet s rodinou“. Nehlásí nic do společného seznamu. */
    const val TEST_KEY = "zkouska"

    /** Zkouška s rodinou: SMS s ANO / NE a v ntfy tlačítka. Rodina odpoví a senior uvidí, že to funguje. */
    fun sendTest(ctx: Context) {
        val p = prefs(ctx)
        p.edit().remove("v_$TEST_KEY").remove("by_$TEST_KEY").apply()
        val senior = Prefs(ctx).seniorName
        Family.notify(ctx, "Zkouška",
            "Toto je jen zkouška, nic se neděje. Takhle vám přijde zpráva, když $senior dostane něco podezřelého. " +
            "Odpovězte prosím, ať víme, že vaše odpověď dojde.", urgent = false, reviewKey = TEST_KEY)
    }

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

    /** Zpracuje jednu odpověď z kanálu ntfy. Vrací true, pokud se něco změnilo. */
    private fun apply(ctx: Context, message: String): Boolean {
        val parts = message.trim().split('|')
        if (parts.size != 3) return false
        val (verdict, key, token) = parts
        if (verdict != "ok" && verdict != "scam") return false
        if (prefs(ctx).getString("t_$key", null) != token) return false
        return record(ctx, key, if (verdict == "ok") Verdict.OK else Verdict.SCAM, null, null)
    }

    /** Kdo zprávu posoudil (jméno z rodiny), nebo null. */
    fun decidedBy(ctx: Context, key: String): String? = prefs(ctx).getString("by_$key", null)

    /**
     * Uloží rozhodnutí rodiny, dá vědět seniorovi a potvrdí to rodině.
     * byNumber = odpověď přišla SMS z tohoto čísla.
     */
    fun record(ctx: Context, key: String, verdict: Verdict, byName: String?, byNumber: String?): Boolean {
        val p = prefs(ctx)
        val v = if (verdict == Verdict.OK) "ok" else "scam"
        if (p.getString("v_$key", null) == v) return false
        p.edit().putString("v_$key", v).apply { if (byName != null) putString("by_$key", byName) else remove("by_$key") }.apply()

        val prefsMain = Prefs(ctx)
        val ok = verdict == Verdict.OK
        if (key == TEST_KEY) { testDone(ctx, ok, byName ?: prefsMain.familyName, byNumber); return true }
        // Rodina schválila SMS od odesílatele se jménem: příště už jeho reklamu nehlásit
        if (ok && key.startsWith("s")) key.drop(1).toLongOrNull()?.let { SmsStore.byId(ctx, it) }
            ?.let { TrustedSenders.learn(ctx, it.address, it.body) }
        // Společný seznam: podvod nahlásit (aby varoval i ostatní), „v pořádku“ hlášení oslabí
        val shared = SharedList.onVerdict(ctx, key, scam = !ok)
        val who = byName ?: prefsMain.familyName
        val tap = Intent(ctx, DruhyNazorActivity::class.java)
            .putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Notify.show(ctx, 7000 + (key.hashCode() and 0xFFF), if (ok) Notify.CH_MSG else Notify.CH_WARN,
            if (ok) "✅ $who: zpráva je v pořádku" else "⛔ $who: je to podvod",
            if (ok) "Zpráva je v pořádku, můžete ji otevřít." else "Na nic neklikejte, nic neplaťte a na zprávu neodpovídejte.",
            tap, if (ok) Ui.OK else Color.RED)

        val senior = prefsMain.seniorName
        val result = if (ok) "$senior uvidí, že je zpráva v pořádku, a odkaz půjde otevřít."
                     else "$senior uvidí, že je to potvrzený podvod." +
                         if (shared) " Číslo a odkazy jsme nahlásili do společného seznamu, aby varovaly i ostatní rodiny." else ""
        if (byNumber != null) {
            Family.sendSms(ctx, byNumber, "Postaráno: Díky. $result")
            Family.sms(ctx, "Postaráno: Vyřízeno. $who napsal(a): ${if (ok) "zpráva je v pořádku" else "je to podvod"}. Nemusíte nic dělat.", except = byNumber)
        } else if (prefsMain.ntfyEnabled) {
            Family.ntfy(ctx, "Hotovo", result, urgent = false)
        }
        return true
    }

    /** Odpověď na zkoušku: senior uvidí, kdo odpověděl, rodina dostane potvrzení. Nic se nikam nehlásí. */
    private fun testDone(ctx: Context, ok: Boolean, who: String, byNumber: String?) {
        val prefsMain = Prefs(ctx)
        val answer = if (ok) "ANO" else "NE"
        val tap = Intent(ctx, DruhyNazorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Notify.show(ctx, 7999, Notify.CH_MSG, "✅ Zkouška vyšla",
            "$who odpověděl(a) $answer. Odpovědi rodiny fungují.", tap, Ui.OK)
        val text = "Zkouška vyšla. Vaše odpověď $answer dorazila na telefon ${prefsMain.seniorGenitive}. " +
            "Stejně budete odpovídat, až přijde opravdu podezřelá zpráva."
        if (byNumber != null) Family.sendSms(ctx, byNumber, "Postaráno: $text")
        else if (prefsMain.ntfyEnabled) Family.ntfy(ctx, "Zkouška vyšla", "$who odpověděl(a) $answer. $text", urgent = false)
    }

    // ---------- Odpovědi rodiny přes SMS ----------

    /** Věta na konec SMS pro rodinu. Každá čekající zpráva má krátké číslo 1–9. */
    fun smsQuestion(ctx: Context, key: String): String {
        val p = prefs(ctx)
        val n = (p.getInt("next_n", 0) % 9) + 1
        p.edit().putInt("next_n", n).putString("p_$n", key).putLong("pt_$n", System.currentTimeMillis()).apply()
        return "Je to v pořádku? Odpovězte ANO $n, nebo NE $n."
    }

    private val replyRegex = Regex("^\\s*(ano|ne)\\s*([1-9])?\\s*[.!]*\\s*$", RegexOption.IGNORE_CASE)

    /**
     * SMS od člena rodiny ve tvaru „ANO 2“ / „NE“. Vrací true, když byla zpracovaná jako odpověď
     * (pak se neukazuje jako běžná zpráva).
     */
    fun handleSmsReply(ctx: Context, from: String, body: String): Boolean {
        val member = Prefs(ctx).familyByNumber(from) ?: return false
        val m = replyRegex.find(body) ?: return false
        val verdict = if (m.groupValues[1].equals("ano", ignoreCase = true)) Verdict.OK else Verdict.SCAM
        val p = prefs(ctx)
        val week = 7L * 24 * 3600 * 1000
        val now = System.currentTimeMillis()
        val n = m.groupValues[2].toIntOrNull()
            // Bez čísla: poslední otázka
            ?: (1..9).filter { now - p.getLong("pt_$it", 0L) < week }.maxByOrNull { p.getLong("pt_$it", 0L) }
            ?: return false
        if (now - p.getLong("pt_$n", 0L) > week) return false
        val key = p.getString("p_$n", null) ?: return false
        if (!record(ctx, key, verdict, member.name, from)) {
            Family.sendSms(ctx, from, "Postaráno: Tahle zpráva už je vyřízená. Nemusíte nic dělat.")
        }
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
