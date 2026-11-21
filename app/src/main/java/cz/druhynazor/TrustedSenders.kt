package cz.druhynazor

import android.content.Context

/**
 * Odesílatelé se jménem (např. „TescoMobile“), jejichž zprávu rodina jednou označila „Je to v pořádku“.
 * Pamatujeme si i stránky z odkazů té zprávy. Další reklama od stejného odesílatele na stejné stránky
 * už rodinu neruší. Odkaz na jinou stránku se kontroluje jako dřív (jméno se dá napodobit).
 */
object TrustedSenders {
    private const val NO_LINK = "-"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("odesilatele", Context.MODE_PRIVATE)

    fun learned(ctx: Context, sender: String): Set<String>? {
        if (!ScamCheck.isServiceSender(sender)) return null
        return prefs(ctx).getStringSet(ScamCheck.norm(sender), null)?.toSet()
    }

    fun learn(ctx: Context, sender: String, body: String) {
        if (!ScamCheck.isServiceSender(sender)) return
        val key = ScamCheck.norm(sender)
        if (key.isEmpty()) return
        val now = (prefs(ctx).getStringSet(key, null) ?: emptySet()) + ScamCheck.sites(body) + NO_LINK
        prefs(ctx).edit().putStringSet(key, now.toSet()).apply()
    }

    /** Kontrola SMS i s ohledem na odesílatele. */
    fun check(ctx: Context, body: String, sender: String): ScamCheck.Result =
        ScamCheck.check(body, sender, learned(ctx, sender))
}
