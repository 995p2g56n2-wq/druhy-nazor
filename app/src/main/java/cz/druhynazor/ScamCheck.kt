package cz.druhynazor

/**
 * Jednoduchá kontrola zpráv přímo v telefonu (nic se nikam neposílá).
 * Ve skutečné aplikaci se tato pravidla budou stahovat ze serveru a aktualizovat podle aktuálních podvodů.
 *
 * Reklama od známého odesílatele (Tesco Mobile, CCC, Kaufland…) rodinu neruší. Jméno odesílatele
 * ale podvodník umí napodobit, proto se nevěří samotnému jménu: odkaz ve zprávě musí vést
 * na oficiální stránku toho odesílatele a zpráva nesmí chtít kód, PIN, převod peněz ani psát jinam.
 */
object ScamCheck {

    enum class Level { OK, WARN, SCAM }

    /** promo = reklama nebo informace od ověřeného odesílatele (důvod se ukáže v zelené kartě). */
    data class Result(val level: Level, val reason: String, val signs: List<String>, val promo: Boolean = false)

    /** danger = znak, kvůli kterému zprávu nikdy nebereme jako reklamu, ať je od kohokoli. */
    private class Rule(val regex: Regex, val weight: Int, val sign: String, val reason: String, val danger: Boolean = false)

    private fun r(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

    // Koncovky adres, které poznáme i bez „http“ a „www“ (např. „temu.to/abc“ nebo „alza.cz“)
    private const val TLDS = "cz|sk|com|eu|net|org|info|top|xyz|to|me|io|app|shop|online|site|link|ly|co|de|at|pl|hu|uk|" +
        "biz|club|live|icu|vip|cc|ru|cn|store|click|buzz|life|space|website|fun|pw|tk|ml|ga|cf|gq|ws|su|tv|in|us|cloud"

    /** Adresy stránek ve zprávě (s „http“, s „www“ i holé „neco.cz/…“). */
    private val linkRegex = Regex(
        "(?<![\\w@.-])(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:$TLDS))(?![a-z0-9-])(?::\\d+)?(?:/\\S*)?",
        RegexOption.IGNORE_CASE
    )

    private val rules = listOf(
        Rule(r("(https?://|www\\.|bit\\.ly|klikn)"), 3,
            "Odkaz", "Ve zprávě je odkaz na neznámou stránku."),
        Rule(r("(doplat|uhra[dď]|zaplať|zaplat|poplat|nedoplat)"), 2,
            "Chce zaplatit", "Chce po vás zaplatit přes odkaz. Pošta ani úřady to takhle nedělají."),
        Rule(r("(nové číslo|nove cislo|rozbil.{0,3} se mi|ztratil.{0,12}(telefon|mobil))"), 4,
            "Nové číslo", "Někdo se vydává za vaše dítě nebo vnouče z neznámého čísla.", danger = true),
        Rule(r("(pošl\\w* (mi )?peníze|posl\\w* (mi )?penize|potřebuju.{0,20}peníze|potrebuju.{0,20}penize|" +
                "převe\\w* (\\w+ )?(peníze|penize|úspor|uspor|na (\\w+ )?účet|na (\\w+ )?ucet)|bezpečný účet|bezpecny ucet)"), 4,
            "Chce peníze", "Chce, abyste rychle poslali nebo převedli peníze.", danger = true),
        Rule(r("(kód z sms|kod z sms|\\bpin\\b|heslo|číslo karty|cislo karty|údaje (z |o )?kart|udaje (z |o )?kart|" +
                "přihlašovací|prihlasovaci|ověřovací kód|overovaci kod)"), 4,
            "Chce kód", "Chce kód, PIN nebo heslo. Banka se na ně nikdy neptá.", danger = true),
        Rule(r("(z banky|vaše banka|vase banka|bankovní účet|bankovni ucet|policie|policist)"), 2,
            "Banka / policie", "Vydává se za banku nebo policii.", danger = true),
        Rule(r("(do 24 hodin|ihned|okamžitě|okamzite|naléhav|naleha|posledni vyzva|poslední výzva)"), 1,
            "Spěchá", "Tlačí vás ke spěchu, abyste nestihli přemýšlet."),
        Rule(r("(whatsapp|telegram)"), 1,
            "Jinam psát", "Chce, abyste psali jinam.", danger = true),
        Rule(r("(vyhr[aá]l|výhr|bitcoin|kryptom|investic|zhodnocení)"), 3,
            "Výhra / investice", "Slibuje výhru nebo snadný zisk."),
        Rule(r("(česká pošta|ceska posta|zásilk|zasilk|balík|balik|\\bppl\\b|\\bdpd\\b|zásilkovn)"), 1,
            "Jako doručení", "Tváří se jako zpráva od pošty nebo přepravce.")
    )

    private const val LINK_SIGN = "Odkaz"

    /**
     * Známí odesílatelé, u kterých se jméno neshoduje s adresou stránky (nebo jich mají víc).
     * Klíč = jméno odesílatele malými písmeny bez mezer a pomlček. Ostatní (Alza, Notino, Kaufland…)
     * poznáme samy: jméno odesílatele = název stránky (alza.cz).
     * Banky tu záměrně nejsou: banka odkaz v SMS neposílá.
     */
    private val knownSenders: Map<String, Pair<String, Set<String>>> = listOf(
        listOf("Tesco Mobile", "tescomobile", "tescomobilecz") to setOf("tescomobile.cz", "tescomobile.com"),
        listOf("Tesco", "tesco", "itesco", "tescocz", "clubcard") to setOf("tesco.cz", "itesco.cz", "clubcard.cz", "tesco.com"),
        listOf("CCC", "ccc", "cccshoes") to setOf("ccc.eu"),
        listOf("T-Mobile", "tmobile", "tmobilecz") to setOf("t-mobile.cz"),
        listOf("O2", "o2", "o2cz") to setOf("o2.cz", "o2tv.cz", "o2family.cz"),
        listOf("Vodafone", "vodafone", "vodafonecz") to setOf("vodafone.cz"),
        listOf("Kaufland", "kaufland") to setOf("kaufland.cz", "kaufland.com"),
        listOf("Lidl", "lidl", "lidlplus", "lidlcz") to setOf("lidl.cz", "lidl.com", "lidlplus.com"),
        listOf("Albert", "albert", "albertcz") to setOf("albert.cz"),
        listOf("Billa", "billa") to setOf("billa.cz"),
        listOf("Penny", "penny", "pennymarket") to setOf("penny.cz"),
        listOf("Globus", "globus") to setOf("globus.cz"),
        listOf("dm", "dm", "dmdrogerie") to setOf("dm.cz", "mojedm.cz"),
        listOf("Dr.Max", "drmax") to setOf("drmax.cz"),
        listOf("Rohlík", "rohlik", "rohlikcz") to setOf("rohlik.cz"),
        listOf("Košík", "kosik", "kosikcz") to setOf("kosik.cz"),
        listOf("Alza", "alza", "alzacz") to setOf("alza.cz", "alza.sk"),
        listOf("Mall", "mall", "mallcz") to setOf("mall.cz"),
        listOf("Notino", "notino") to setOf("notino.cz"),
        listOf("Datart", "datart") to setOf("datart.cz"),
        listOf("Pepco", "pepco") to setOf("pepco.cz", "pepco.eu"),
        listOf("Sinsay", "sinsay") to setOf("sinsay.com"),
        listOf("Deichmann", "deichmann") to setOf("deichmann.com", "deichmann.cz"),
        listOf("Baťa", "bata") to setOf("bata.com", "bata.cz"),
        listOf("Tchibo", "tchibo") to setOf("tchibo.cz"),
        listOf("IKEA", "ikea") to setOf("ikea.com", "ikea.cz"),
        listOf("Zásilkovna", "zasilkovna", "packeta") to setOf("zasilkovna.cz", "packeta.com", "packeta.cz"),
        listOf("PPL", "ppl", "pplcz") to setOf("ppl.cz"),
        listOf("DPD", "dpd", "dpdcz") to setOf("dpd.cz", "dpd.com"),
        listOf("GLS", "gls", "glscz") to setOf("gls-group.eu", "gls-group.com", "gls-czech.com"),
        listOf("Česká pošta", "ceskaposta", "postaonline") to setOf("ceskaposta.cz", "postaonline.cz"),
        listOf("České dráhy", "cd", "ceskedrahy") to setOf("cd.cz"),
        listOf("RegioJet", "regiojet") to setOf("regiojet.cz", "regiojet.com")
    ).flatMap { (names, domains) -> names.drop(1).map { it to (names[0] to domains) } }.toMap()

    // U jména odesílatele, které sedí na název stránky, věříme jen běžným koncovkám
    private val nameMatchTlds = setOf("cz", "sk", "eu", "com")

    fun norm(sender: String) = sender.lowercase()
        .replace('á', 'a').replace('č', 'c').replace('ď', 'd').replace('é', 'e').replace('ě', 'e')
        .replace('í', 'i').replace('ň', 'n').replace('ó', 'o').replace('ř', 'r').replace('š', 's')
        .replace('ť', 't').replace('ú', 'u').replace('ů', 'u').replace('ý', 'y').replace('ž', 'z')
        .filter { it.isLetterOrDigit() }

    /** Odesílatel se jménem (např. „TescoMobile“) nebo krátkým číslem (např. 4603), ne běžný mobil. */
    fun isServiceSender(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val s = sender.trim()
        if (s.any { it.isLetter() }) return true
        val digits = s.filter { it.isDigit() }
        return !s.startsWith("+") && digits.length in 3..6
    }

    /** Hlavní část adresy: „www.akce.tescomobile.cz“ → „tescomobile.cz“. */
    fun siteOf(host: String): String {
        val parts = host.lowercase().trimEnd('.').split('.')
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    /** Hlavní části adres všech odkazů ve zprávě. */
    fun sites(text: String): Set<String> =
        linkRegex.findAll(text)
            .filter { m ->
                // „ceny.To je…“ není odkaz: krátké koncovky, které jsou i běžná slova, bereme jen s „/“, „http“ nebo „www“
                val tld = m.groupValues[1].substringAfterLast('.').lowercase()
                tld !in wordTlds || '/' in m.value || m.value.startsWith("http", true) || m.value.startsWith("www.", true)
            }
            .map { siteOf(it.groupValues[1]) }
            .filter { s -> s.substringBefore('.').any { it.isLetter() } }
            .toSet()

    private val wordTlds = setOf("to", "me", "in", "co", "at", "de", "us", "tv", "ly", "cc", "su", "ws", "io", "app", "fun", "life", "live", "info")

    /** Odkaz vede na oficiální stránku odesílatele? */
    private fun siteBelongs(senderKey: String, site: String, official: Set<String>): Boolean {
        if (site in official) return true
        val label = site.substringBefore('.')
        val tld = site.substringAfterLast('.')
        return senderKey.length >= 3 && norm(label) == senderKey && tld in nameMatchTlds
    }

    /**
     * Kontrola zprávy. sender = odesílatel SMS (jméno nebo číslo), null u zpráv z chatů.
     * learned = stránky, které rodina u tohoto odesílatele už jednou schválila (null = žádné).
     */
    fun check(text: String, sender: String? = null, learned: Set<String>? = null): Result {
        var score = 0
        val hits = mutableListOf<Rule>()
        for (rule in rules) {
            if (rule.regex.containsMatchIn(text)) {
                score += rule.weight
                hits += rule
            }
        }
        val links = sites(text)
        // Odkaz bez „http“ a „www“ (např. „temu.to/x“) je pořád odkaz
        if (links.isNotEmpty() && hits.none { it.sign == LINK_SIGN }) {
            val linkRule = rules.first { it.sign == LINK_SIGN }
            score += linkRule.weight
            hits += linkRule
        }

        if (isServiceSender(sender)) {
            val key = norm(sender!!)
            val known = knownSenders[key]
            val official = (known?.second ?: emptySet()) + (learned ?: emptySet())
            val name = known?.first ?: sender.trim()
            val foreign = links.filterNot { siteBelongs(key, it, official) }
            val trusted = known != null || !learned.isNullOrEmpty() ||
                (links.isNotEmpty() && foreign.isEmpty()) // jméno odesílatele sedí na stránku v odkazu
            val danger = hits.any { it.danger }
            if (trusted && !danger && foreign.isEmpty()) {
                val why = if (known == null && !learned.isNullOrEmpty()) "Rodina už zprávy od $name schválila."
                          else if (links.isEmpty()) "Zpráva od $name, nechce žádné kódy ani peníze."
                          else "Odkaz vede na oficiální stránku ${links.first()}."
                return Result(Level.OK, "Reklama nebo informace od $name. $why", listOf("Reklama"), promo = true)
            }
            if (trusted && foreign.isNotEmpty()) {
                // Tváří se jako známý odesílatel, ale odkaz vede jinam: typický podvod s napodobeným jménem
                score += 2
                // Vyšší váha jen kvůli pořadí důvodů: tenhle důvod se ukáže jako první
                val spoof = Rule(r("x"), 9, "Odkaz jinam",
                    "Tváří se jako zpráva od $name, ale odkaz vede na cizí stránku ${foreign.first()}.")
                hits += spoof
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
