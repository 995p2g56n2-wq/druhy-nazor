package cz.druhynazor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth

/**
 * Čtení dopisu přes umělou inteligenci (Claude). Pošle se jen přečtený text dopisu (bez rodných
 * čísel), nikdy fotka. Jde přes náš malý server, kde je schovaný klíč (server/dopisy).
 * Když něco nejde (bez internetu, došel kredit, vyčerpaný limit), dopis se přečte jen v telefonu.
 */
object AiLetters {
    const val MODE_PHONE = 0
    const val MODE_BASIC = 1
    const val MODE_BETTER = 2

    /** Dopisů zdarma za měsíc na jeden telefon. */
    const val FREE_PER_MONTH = 20

    private const val APP_KEY = "postarano-dopisy-v1"

    /** Adresa serveru přijde se společným seznamem (server/dopisy-adresa.txt). Prázdná = zatím není. */
    fun server(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences("spolecny_seznam", Context.MODE_PRIVATE)
            .getString("dopisy_server", "")?.trim()?.trimEnd('/') ?: ""

    fun available(ctx: Context) = server(ctx).startsWith("https://")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dopisy_ai", Context.MODE_PRIVATE)

    /** Kolik dopisů už tento měsíc četla umělá inteligence. */
    fun usedThisMonth(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString("mesic", "") == YearMonth.now().toString()) p.getInt("pocet", 0) else 0
    }

    private fun countOne(ctx: Context) {
        val used = usedThisMonth(ctx)
        prefs(ctx).edit().putString("mesic", YearMonth.now().toString()).putInt("pocet", used + 1).apply()
    }

    /** Použít umělou inteligenci na tento dopis? */
    fun shouldUse(ctx: Context): Boolean {
        val mode = Prefs(ctx).letterMode
        return mode != MODE_PHONE && available(ctx) && usedThisMonth(ctx) < FREE_PER_MONTH
    }

    /** Poslední problém (do Více), nebo null. */
    @Volatile var lastProblem: String? = null

    /**
     * Zeptá se serveru. Volat mimo hlavní vlákno. Vrátí null, když to nejde, a dopis se pak
     * přečte jen v telefonu.
     */
    fun ask(ctx: Context, text: String): LetterReader.AiHints? {
        lastProblem = null
        val base = server(ctx)
        if (!base.startsWith("https://")) return null
        val mode = if (Prefs(ctx).letterMode == MODE_BETTER) "presnejsi" else "zakladni"
        return try {
            val c = (URL("$base/dopis").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("content-type", "application/json; charset=utf-8")
                setRequestProperty("x-postarano", APP_KEY)
            }
            val body = JSONObject().put("text", LetterReader.forAi(text).take(15_000)).put("rezim", mode)
                .put("dnes", LocalDate.now().toString())
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            c.disconnect()
            val json = JSONObject(raw.ifEmpty { "{}" })
            if (!json.optBoolean("ok")) {
                lastProblem = when (json.optString("chyba")) {
                    "kredit" -> "Umělá inteligence teď nečte, došel kredit."
                    "pretizeno" -> "Umělá inteligence je teď přetížená."
                    else -> "Umělá inteligence teď nečte."
                }
                Log.w("DruhyNazor", "Dopis přes AI: chyba ${json.optString("chyba")} ($code)")
                return null
            }
            countOne(ctx)
            parse(json.getJSONObject("vysledek"), json.optString("model"))
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Dopis přes AI se nepodařilo", e)
            lastProblem = "Umělá inteligence teď nečte, telefon asi nemá internet."
            null
        }
    }

    private fun strings(o: JSONObject, name: String): List<String> {
        val a = o.optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun parse(o: JSONObject, model: String) = LetterReader.AiHints(
        sender = o.optString("odesilatel"),
        kind = LetterReader.aiKind(o.optString("druh")),
        what = o.optString("za_co"),
        amountText = o.optString("castka"),
        dueText = o.optString("splatnost"),
        scam = strings(o, "podvod"),
        warn = strings(o, "pozor"),
        summary = o.optString("shrnuti"),
        sure = o.optBoolean("jista", false),
        model = model,
    )

    fun modeName(mode: Int) = when (mode) {
        MODE_BASIC -> "Základní"
        MODE_BETTER -> "Přesnější"
        else -> "Jen v telefonu"
    }
}
