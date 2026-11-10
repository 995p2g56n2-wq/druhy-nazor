package cz.druhynazor

/**
 * Jednoduchá kontrola zpráv přímo v telefonu (nic se nikam neposílá).
 * Ve skutečné aplikaci se tato pravidla budou stahovat ze serveru a aktualizovat podle aktuálních podvodů.
 */
object ScamCheck {

    enum class Level { OK, WARN, SCAM }

    data class Result(val level: Level, val reason: String, val signs: List<String>)

    private class Rule(val regex: Regex, val weight: Int, val sign: String, val reason: String)

    private fun r(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

    private val rules = listOf(
        Rule(r("(https?://|www\\.|\\.top\\b|\\.xyz\\b|\\.info\\b|bit\\.ly|klikn)"), 3,
            "Odkaz", "Ve zprávě je odkaz na neznámou stránku."),
        Rule(r("(doplat|uhra[dď]|zaplať|zaplat|poplat|nedoplat)"), 2,
            "Chce zaplatit", "Chce po vás zaplatit přes odkaz. Pošta ani úřady to takhle nedělají."),
        Rule(r("(nové číslo|nove cislo|rozbil.{0,3} se mi|ztratil.{0,12}(telefon|mobil))"), 4,
            "Nové číslo", "Někdo se vydává za vaše dítě nebo vnouče z neznámého čísla."),
        Rule(r("(pošl\\w* (mi )?peníze|posl\\w* (mi )?penize|potřebuju.{0,20}peníze|převe\\w*|bezpečný účet|bezpecny ucet)"), 4,
            "Chce peníze", "Chce, abyste rychle poslali nebo převedli peníze."),
        Rule(r("(kód z sms|kod z sms|\\bpin\\b|heslo|číslo karty|cislo karty|přihlašovací|prihlasovaci|ověřovací kód)"), 4,
            "Chce kód", "Chce kód, PIN nebo heslo. Banka se na ně nikdy neptá."),
        Rule(r("(z banky|vaše banka|bankovní účet|policie|policist)"), 2,
            "Banka / policie", "Vydává se za banku nebo policii."),
        Rule(r("(do 24 hodin|ihned|okamžitě|okamzite|naléhav|posledni vyzva|poslední výzva)"), 1,
            "Spěchá", "Tlačí vás ke spěchu, abyste nestihli přemýšlet."),
        Rule(r("(whatsapp|telegram)"), 1,
            "Jinam psát", "Chce, abyste psali jinam."),
        Rule(r("(vyhr[aá]l|výhr|bitcoin|kryptom|investic|zhodnocení)"), 3,
            "Výhra / investice", "Slibuje výhru nebo snadný zisk."),
        Rule(r("(česká pošta|ceska posta|zásilk|zasilk|balík|balik|\\bppl\\b|\\bdpd\\b|zásilkovn)"), 1,
            "Jako doručení", "Tváří se jako zpráva od pošty nebo přepravce.")
    )

    fun check(text: String): Result {
        var score = 0
        val hits = mutableListOf<Rule>()
        for (rule in rules) {
            if (rule.regex.containsMatchIn(text)) {
                score += rule.weight
                hits += rule
            }
        }
        hits.sortByDescending { it.weight }
        val level = when {
            score >= 6 -> Level.SCAM
            score >= 3 -> Level.WARN
            else -> Level.OK
        }
        val reason = hits.firstOrNull()?.reason ?: "Nikdo nechce peníze, kódy ani kliknutí na odkaz."
        return Result(level, reason, hits.map { it.sign })
    }

    fun headline(level: Level) = when (level) {
        Level.SCAM -> "PODVOD"
        Level.WARN -> "POZOR"
        Level.OK -> "V POŘÁDKU"
    }

    fun advice(level: Level) = when (level) {
        Level.SCAM -> "Na nic neklikejte a nic neplaťte."
        Level.WARN -> "Zatím nic nedělejte, rodina se podívá."
        Level.OK -> "Vypadá to jako běžná zpráva."
    }
}
