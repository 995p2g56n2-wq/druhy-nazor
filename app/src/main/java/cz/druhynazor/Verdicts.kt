package cz.druhynazor

import android.content.Context
import android.content.Intent
import android.graphics.Color

/**
 * Rodina potvrzuje podezřelé zprávy odpovědí na SMS: „ANO 2“ = v pořádku, „NE 2“ = podvod.
 * Počítá se jen odpověď z čísla, které je v seznamu rodiny.
 */
object Verdicts {
    enum class Verdict { OK, SCAM }

    /** Klíč zkušební otázky „Vyzkoušet s rodinou“. Nehlásí nic do společného seznamu. */
    const val TEST_KEY = "zkouska"

    /** Zkouška s rodinou: SMS s otázkou ANO / NE. Rodina odpoví a senior uvidí, že to funguje. */
    fun sendTest(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val p = prefs(ctx)
        p.edit().remove("v_$TEST_KEY").remove("by_$TEST_KEY").apply()
        val senior = Prefs(ctx).seniorName
        Family.notify(ctx, "Zkouška, nic se neděje.",
            "Takhle vám napíšu, když $senior dostane něco podezřelého.", urgent = false, reviewKey = TEST_KEY, done = done)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("verdikty", Context.MODE_PRIVATE)

    fun keyForSms(id: Long) = "s$id"
    fun keyForText(from: String, text: String) = "c" + (from + text).hashCode().toUInt().toString(16)

    fun get(ctx: Context, key: String): Verdict? = when (prefs(ctx).getString("v_$key", null)) {
        "ok" -> Verdict.OK
        "scam" -> Verdict.SCAM
        else -> null
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
        SharedList.onVerdict(ctx, key, scam = !ok)
        val who = byName ?: prefsMain.familyName
        val tap = Intent(ctx, DruhyNazorActivity::class.java)
            .putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Notify.show(ctx, 7000 + (key.hashCode() and 0xFFF), if (ok) Notify.CH_MSG else Notify.CH_WARN,
            if (ok) "✅ $who: zpráva je v pořádku" else "⛔ $who: je to podvod",
            if (ok) "Zpráva je v pořádku, můžete ji otevřít." else "Na nic neklikejte, nic neplaťte a na zprávu neodpovídejte.",
            tap, if (ok) Ui.OK else Color.RED)

        // Tomu, kdo odpověděl, už nic neposíláme (každá SMS stojí kredit). Ostatním z rodiny jen krátce, ať to neřeší dvakrát.
        if (byNumber != null && prefsMain.familyContacts.size > 1) {
            Family.sms(ctx, "Postaráno: Vyřízeno, $who napsal(a): ${if (ok) "v pořádku" else "podvod"}. Nemusíte nic dělat.", except = byNumber)
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
        val text = "Zkouška vyšla, vaše odpověď $answer dorazila. Stejně odpovíte, až přijde opravdu podezřelá zpráva."
        if (byNumber != null) Family.sendSms(ctx, byNumber, "Postaráno: $text")
    }

    // ---------- Odpovědi rodiny přes SMS ----------

    /** Věta na konec SMS pro rodinu. Každá čekající zpráva má krátké číslo 1–9. */
    fun smsQuestion(ctx: Context, key: String): String {
        val p = prefs(ctx)
        val n = (p.getInt("next_n", 0) % 9) + 1
        p.edit().putInt("next_n", n).putString("p_$n", key).putLong("pt_$n", System.currentTimeMillis()).apply()
        return "Odpovězte ANO $n = v pořádku, NE $n = podvod."
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
}
