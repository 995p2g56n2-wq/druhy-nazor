package cz.druhynazor

import android.content.Context

/**
 * Odesílatelé (jméno jako „TescoMobile“ i běžné číslo), jejichž zprávu rodina jednou označila „Je to v pořádku“.
 * Pamatujeme si i stránky z odkazů té zprávy. Další podobná zpráva od stejného odesílatele na stejné stránky
 * už rodinu neruší. Odkaz na jinou stránku nebo žádost o peníze, kód či „nové číslo“ se hlásí dál.
 */
object TrustedSenders {
    private const val NO_LINK = "-"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("odesilatele", Context.MODE_PRIVATE)

    /** „+420 777 123 456“ i „777123456“ je stejné číslo; jména malými písmeny bez mezer. */
    private fun keyOf(sender: String): String {
        val k = ScamCheck.norm(sender)
        return if (k.all { it.isDigit() } && k.length > 9) k.takeLast(9) else k
    }

    fun learned(ctx: Context, sender: String): Set<String>? {
        return prefs(ctx).getStringSet(keyOf(sender), null)?.toSet()
    }

    fun learn(ctx: Context, sender: String, body: String) {
        val key = keyOf(sender)
        if (key.isEmpty()) return
        val now = (prefs(ctx).getStringSet(key, null) ?: emptySet()) + ScamCheck.sites(body) + NO_LINK
        prefs(ctx).edit().putStringSet(key, now.toSet()).apply()
    }

    /** Kontrola SMS i s ohledem na odesílatele. */
    fun check(ctx: Context, body: String, sender: String): ScamCheck.Result =
        ScamCheck.check(body, sender, learned(ctx, sender))
}
