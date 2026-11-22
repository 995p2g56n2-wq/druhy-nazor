package cz.druhynazor

import java.time.LocalDate

/**
 * Přečte text dopisu (z fotky) a řekne tři věci: o co jde, jestli je to pravé, co udělat a do kdy.
 * Všechno běží přímo v telefonu, text dopisu se nikam neposílá.
 *
 * Kontroly pravosti (propojené s Druhým názorem):
 * - chce číslo karty, PIN nebo heslo, platbu v bitcoinech nebo kupónem → podvod
 * - tváří se jako český úřad nebo firma, ale peníze mají jít na cizí účet → podvod
 * - odkaz nebo telefon ze společného seznamu podvodů → podvod (doplní LetterActivity)
 * - chce peníze a odesílatele neznám, číslo účtu nesedí, výhrůžky a spěch → pozor
 */
object LetterReader {

    enum class Kind { PAY, DEBT, EXEKUCE, COURT, REFUND, PENSION, AD, INFO }

    data class Account(val text: String, val valid: Boolean)

    data class Letter(
        val sender: String?,
        val knownSender: Boolean,
        val kind: Kind,
        /** Co se platí: „televizní poplatek“, „elektřinu“… */
        val what: String?,
        /** Částka v haléřích. */
        val amount: Long?,
        val due: LocalDate?,
        val accounts: List<Account>,
        val ibans: List<String>,
        val vs: String?,
        val sites: Set<String>,
        val phones: Set<String>,
        val level: ScamCheck.Level,
        /** Důvody pro barvu, nejdůležitější první. */
        val reasons: List<String>,
        val textLength: Int,
    ) {
        val wantsMoney get() = kind in setOf(Kind.PAY, Kind.DEBT, Kind.EXEKUCE) || (kind == Kind.COURT && amount != null)
    }

    // ---------- Známí odesílatelé ----------

    /** Jméno do věty, vzory v textu, oficiální stránky, co obvykle chtějí zaplatit. */
    private class Known(val name: String, val patterns: List<String>, val sites: Set<String>, val what: String? = null)

    private val known = listOf(
        Known("Česká televize", listOf("česká televize", "ceska televize", "televizní poplat", "ceskatelevize"), setOf("ceskatelevize.cz"), "televizní poplatek"),
        Known("Český rozhlas", listOf("český rozhlas", "cesky rozhlas", "rozhlasový poplat"), setOf("rozhlas.cz"), "rozhlasový poplatek"),
        Known("Česká správa sociálního zabezpečení", listOf("česká správa sociálního", "ceska sprava socialniho", "čssz", "okresní správa sociálního", "pražská správa sociálního"), setOf("cssz.cz")),
        Known("finanční úřad", listOf("finanční úřad", "financni urad", "finanční správa"), setOf("financnisprava.cz", "mfcr.cz"), "daň"),
        Known("VZP", listOf("všeobecná zdravotní", "vseobecna zdravotni", "vzp čr", "vzp cr"), setOf("vzp.cz"), "zdravotní pojištění"),
        Known("zdravotní pojišťovna", listOf("oborová zdravotní", "zdravotní pojišťovna ministerstva", "česká průmyslová zdravotní", "vojenská zdravotní", "rbp, zdravotní", "zpmv", "čpzp", "ozp"), setOf("ozp.cz", "zpmvcr.cz", "cpzp.cz", "vozp.cz", "rbp213.cz"), "zdravotní pojištění"),
        Known("úřad práce", listOf("úřad práce", "urad prace"), setOf("uradprace.cz", "mpsv.cz")),
        Known("ČEZ", listOf("čez prodej", "cez prodej", "čez distribuce", "čez, a.s", "skupina čez"), setOf("cez.cz"), "elektřinu"),
        Known("E.ON", listOf("e.on energie", "e.on distribuce", "eon.cz", "e.on česká"), setOf("eon.cz"), "elektřinu"),
        Known("innogy", listOf("innogy"), setOf("innogy.cz"), "plyn"),
        Known("Pražská energetika", listOf("pražská energetika", "prazska energetika", "pre.cz"), setOf("pre.cz"), "elektřinu"),
        Known("Pražská plynárenská", listOf("pražská plynárenská", "prazska plynarenska"), setOf("ppas.cz"), "plyn"),
        Known("Centropol", listOf("centropol"), setOf("centropol.cz"), "energie"),
        Known("MND", listOf("mnd energie", "mnd a.s"), setOf("mnd.cz"), "energie"),
        Known("Pražské vodovody a kanalizace", listOf("pražské vodovody", "prazske vodovody"), setOf("pvk.cz"), "vodu"),
        Known("vodárny", listOf("vodovody a kanalizace", "vodárenská", "vodarenska", "vodárny", "veolia", "vak "), setOf("veolia.cz", "smv.cz", "bvk.cz", "ovak.cz", "vakjhlava.cz", "vodarna.cz"), "vodu"),
        Known("O2", listOf("o2 czech republic", "o2 czech", "o2.cz"), setOf("o2.cz"), "telefon"),
        Known("T-Mobile", listOf("t-mobile czech", "t-mobile.cz", "t-mobile"), setOf("t-mobile.cz"), "telefon"),
        Known("Vodafone", listOf("vodafone czech", "vodafone"), setOf("vodafone.cz"), "telefon"),
        Known("Česká pošta", listOf("česká pošta", "ceska posta"), setOf("ceskaposta.cz", "postaonline.cz")),
        Known("Česká spořitelna", listOf("česká spořitelna", "ceska sporitelna"), setOf("csas.cz", "servis24.cz")),
        Known("ČSOB", listOf("čsob", "csob", "československá obchodní banka"), setOf("csob.cz")),
        Known("Komerční banka", listOf("komerční banka", "komercni banka"), setOf("kb.cz")),
        Known("Raiffeisenbank", listOf("raiffeisenbank"), setOf("rb.cz")),
        Known("Moneta", listOf("moneta money bank"), setOf("moneta.cz")),
        Known("Air Bank", listOf("air bank"), setOf("airbank.cz")),
        Known("Fio banka", listOf("fio banka"), setOf("fio.cz")),
        Known("UniCredit Bank", listOf("unicredit"), setOf("unicreditbank.cz")),
        Known("Kooperativa", listOf("kooperativa"), setOf("koop.cz"), "pojištění"),
        Known("Allianz", listOf("allianz"), setOf("allianz.cz"), "pojištění"),
        Known("Generali Česká pojišťovna", listOf("generali", "česká pojišťovna"), setOf("generaliceska.cz", "ceskapojistovna.cz"), "pojištění"),
        Known("ČPP", listOf("česká podnikatelská pojišťovna", "čpp"), setOf("cpp.cz"), "pojištění"),
        Known("Uniqa", listOf("uniqa"), setOf("uniqa.cz"), "pojištění"),
        Known("Policie ČR", listOf("policie české republiky", "policie čr", "policie cr"), setOf("policie.cz", "policie.gov.cz")),
        Known("exekutorský úřad", listOf("exekutorský úřad", "exekutorsky urad", "soudní exekutor", "soudni exekutor"), setOf("ekcr.cz")),
        Known("soud", listOf("okresní soud", "krajský soud", "obvodní soud", "městský soud", "vrchní soud"), setOf("justice.cz")),
        Known("úřad", listOf("městský úřad", "obecní úřad", "magistrát", "úřad městské části", "mestsky urad", "obecni urad"), emptySet()),
    )

    // ---------- Druhy dopisů ----------

    private fun r(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val exekuceRe = r("exekuční příkaz|exekucni prikaz|exekutorský úřad|exekutorsky urad|soudní exekutor|soudni exekutor|zahájení exekuce|zahajeni exekuce|nařízení exekuce|narizeni exekuce")
    private val courtRe = r("rozsud|předvolání|predvolani|platební rozkaz|platebni rozkaz|usnesení soudu|okresní soud|krajský soud|obvodní soud|městský soud")
    private val debtRe = r("upomínk|upomink|výzva k (zaplacení|úhradě)|vyzva k (zaplaceni|uhrade)|předžalobní|predzalobni|dlužn|dluzn|po splatnosti|nedoplatek|neuhrazen")
    private val payRe = r("k úhradě|k uhrade|faktura|vyúčtování|vyuctovani|zaplaťte|zaplatte|uhraďte|uhradte|částka|castka|splatnost|splatné|splatne|složenk|slozenk|platební údaje|platebni udaje|předpis plateb|zálohy|zalohy|poplatek|pojistné|pojistne")
    private val refundRe = r("přeplatek|preplatek|vrátíme vám|vratime vam|bude vám vrácen|bude vam vracen")
    private val pensionRe = r("důchod|duchod|valorizac")
    private val adRe = r("sleva|akční nabídka|akcni nabidka|objednejte|nabízíme|nabizime|výhodn|vyhodn|vyhrál|vyhral|výhr[aou]|vyhr[aou]|zdarma|dárek|darek|katalog|leták|letak")

    // Co se platí (když to neřekne známý odesílatel)
    private val whatRules = listOf(
        r("televizní poplat|televizni poplat") to "televizní poplatek",
        r("rozhlasový poplat|rozhlasovy poplat") to "rozhlasový poplatek",
        r("elektřin|elektrin|elektrick") to "elektřinu",
        r("zemní plyn|zemni plyn|\\bplyn") to "plyn",
        r("vodné|vodne|stočné|stocne") to "vodu",
        r("odpad") to "poplatek za odpad",
        r("daň z nemovit|dan z nemovit") to "daň z nemovitosti",
        r("nájemné|najemne|\\bnájem") to "nájem",
        r("pojistné|pojistne|pojištění|pojisteni") to "pojištění",
        r("telefon|mobilní služb|mobilni sluzb|internet") to "telefon a internet",
    )

    // ---------- Znaky podvodu ----------

    private val cardRe = r("číslo (vaší |platební )?karty|cislo (vasi |platebni )?karty|\\bpin\\b|\\bcvv\\b|\\bcvc\\b|heslo|přihlašovací údaje|prihlasovaci udaje|internetového bankovnictví|internetoveho bankovnictvi")
    private val cryptoRe = r("bitcoin|kryptoměn|kryptomen|\\bbtc\\b|paysafecard|dárkov\\w* kart|darkov\\w* kart|kupón|kupon|google play kart|bitcoinomat")
    private val prizeFeeRe = r("(vyhrál|vyhral|výhr|vyhr)[\\s\\S]{0,300}(poplat|zaplať|zaplat|uhrad)|(poplat|zaplať|zaplat|uhrad)[\\s\\S]{0,300}(vyhrál|vyhral|výhr)")
    private val urgentRe = r("do 24 hodin|do 48 hodin|ihned|okamžitě|okamzite|neprodleně|neprodlene|poslední výzva|posledni vyzva|bez dalšího upozornění")
    private val threatRe = r("zatčen|zatcen|trestní oznámení|trestni oznameni|zabaven|odpojen|vystěhov|vystehov")

    // ---------- Čísla, data, účty ----------

    private val moneyRe = Regex("(?<![\\d,.])(\\d{1,3}(?:[ \\u00A0.]\\d{3})+|\\d{1,7})(?:,(\\d{2}|-{1,2}))?\\s*(?:Kč|Kc|CZK|,-)", RegexOption.IGNORE_CASE)
    private val amountLineRe = r("k úhradě|k uhrade|celkem|částk|castk|zaplatit|zaplaťte|uhraďte|doplatek|nedoplatek|dlužn|dluzn|výše|vyse|poplatek")
    private val dateRe = Regex("(?<!\\d)(\\d{1,2})\\s?\\.\\s?(\\d{1,2})\\s?\\.\\s?(\\d{4}|\\d{2})(?!\\d)")
    private val months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private val monthAscii = listOf("ledna", "unora", "brezna", "dubna", "kvetna", "cervna", "cervence", "srpna", "zari", "rijna", "listopadu", "prosince")
    private val wordDateRe = Regex("(?<!\\d)(\\d{1,2})\\.\\s*(${(months + monthAscii).distinct().joinToString("|")})\\s*(\\d{4})?", RegexOption.IGNORE_CASE)
    private val dueLineRe = r("splatn|zaplaťte do|zaplatte do|uhraďte do|uhradte do|nejpozději|nejpozdeji|do dne|termín|termin|uhradit do|zaplatit do|do\\s+\\d{1,2}\\s?\\.")
    private val accountRe = Regex("(?<![\\d/-])(?:(\\d{1,6})\\s?-\\s?)?(\\d{2,10})\\s?/\\s?(\\d{4})(?!\\d)")
    private val ibanRe = Regex("\\b([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,4})?)\\b")
    private val vsRe = r("(?:variabilní symbol|variabilni symbol|var\\.\\s?symbol|v\\.\\s?s\\.|\\bvs)\\s*[:.]?\\s*(\\d{1,10})")
    private val phoneRe = Regex("(?<![\\d/])(?:\\+420[\\s\\u00A0]?)?([1-9]\\d{2})[\\s\\u00A0]?(\\d{3})[\\s\\u00A0]?(\\d{3})(?![\\d/])")

    /** Kódy českých bank (bez nich to není číslo účtu, ale třeba datum nebo číslo jednací). */
    private val bankCodes = setOf("0100", "0300", "0600", "0710", "0800", "2010", "2060", "2070", "2100", "2200", "2220",
        "2250", "2260", "2275", "2600", "2700", "3030", "3050", "3060", "3500", "4000", "4300", "5500", "5800", "6000",
        "6100", "6200", "6210", "6300", "6363", "6700", "6800", "7910", "7950", "7960", "7970", "7990", "8030", "8040",
        "8060", "8090", "8150", "8190", "8198", "8199", "8200", "8220", "8250", "8255", "8265", "8270", "8280", "8293",
        "8299", "8500")

    /** Kontrolní součet českého čísla účtu (modulo 11). */
    fun accountValid(prefix: String?, number: String): Boolean {
        fun ok(digits: String, weights: IntArray): Boolean {
            val d = digits.padStart(weights.size, '0')
            if (d.length > weights.size) return false
            return d.indices.sumOf { (d[it] - '0') * weights[it] } % 11 == 0
        }
        val pOk = prefix.isNullOrEmpty() || ok(prefix, intArrayOf(10, 5, 8, 4, 2, 1))
        return pOk && ok(number, intArrayOf(6, 3, 7, 9, 10, 5, 8, 4, 2, 1)) && number.trimStart('0').length >= 2
    }

    fun ibanValid(iban: String): Boolean {
        val s = iban.replace(" ", "").uppercase()
        if (s.length < 15 || s.length > 34) return false
        val moved = s.substring(4) + s.substring(0, 4)
        var rem = 0
        for (c in moved) {
            val v = if (c.isDigit()) c.toString() else (c - 'A' + 10).toString()
            for (ch in v) rem = (rem * 10 + (ch - '0')) % 97
        }
        return rem == 1
    }

    private fun parseAmount(m: MatchResult): Long? {
        val whole = m.groupValues[1].replace(" ", "").replace(" ", "").replace(".", "").toLongOrNull() ?: return null
        val cents = m.groupValues[2].takeIf { it.length == 2 && it.all(Char::isDigit) }?.toLong() ?: 0
        if (whole == 0L && cents == 0L) return null
        return whole * 100 + cents
    }

    private fun parseDate(m: MatchResult): LocalDate? = try {
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
        LocalDate.of(y, mo, d)
    } catch (e: Exception) { null }

    private fun parseWordDate(m: MatchResult, today: LocalDate): LocalDate? = try {
        val d = m.groupValues[1].toInt()
        val name = m.groupValues[2].lowercase()
        val mo = (months.indexOf(name).takeIf { it >= 0 } ?: monthAscii.indexOf(name)) + 1
        val y = m.groupValues[3].toIntOrNull() ?: run {
            val thisYear = LocalDate.of(today.year, mo, d)
            if (thisYear.isBefore(today.minusMonths(2))) today.year + 1 else today.year
        }
        LocalDate.of(y, mo, d)
    } catch (e: Exception) { null }

    private fun datesIn(s: String, today: LocalDate): List<LocalDate> =
        dateRe.findAll(s).mapNotNull { parseDate(it) }.toList() + wordDateRe.findAll(s).mapNotNull { parseWordDate(it, today) }.toList()

    // ---------- Čtení ----------

    fun read(raw: String, today: LocalDate = LocalDate.now()): Letter {
        val text = raw.replace('\r', '\n')
        val lower = text.lowercase()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val k = known.firstOrNull { kn -> kn.patterns.any { it in lower } }
        val sender = k?.name ?: guessSender(lines)

        val kind = when {
            exekuceRe.containsMatchIn(text) -> Kind.EXEKUCE
            courtRe.containsMatchIn(text) -> Kind.COURT
            debtRe.containsMatchIn(text) -> Kind.DEBT
            refundRe.containsMatchIn(text) && !Regex("nedoplat", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Kind.REFUND
            payRe.containsMatchIn(text) || moneyRe.containsMatchIn(text) && Regex("účet|ucet|symbol", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Kind.PAY
            pensionRe.containsMatchIn(text) -> Kind.PENSION
            adRe.containsMatchIn(text) -> Kind.AD
            else -> Kind.INFO
        }

        val what = whatRules.firstOrNull { it.first.containsMatchIn(text) }?.second ?: k?.what

        // Částka: nejdřív na řádku „k úhradě / celkem…“ (nebo hned pod ním, tabulky se čtou po sloupcích)
        var amount: Long? = null
        for ((i, line) in lines.withIndex()) {
            if (!amountLineRe.containsMatchIn(line)) continue
            val hit = (i..minOf(i + 2, lines.lastIndex)).asSequence()
                .mapNotNull { j -> moneyRe.findAll(lines[j]).mapNotNull { parseAmount(it) }.lastOrNull() }.firstOrNull()
            if (hit != null) { amount = hit; if (Regex("k úhradě|k uhrade|celkem", RegexOption.IGNORE_CASE).containsMatchIn(line)) break }
        }
        if (amount == null) {
            val all = moneyRe.findAll(text).mapNotNull { parseAmount(it) }.toList()
            amount = if (all.size == 1) all[0] else all.groupingBy { it }.eachCount().filter { it.value > 1 }.maxByOrNull { it.key }?.key ?: all.maxOrNull()
        }

        // Termín: datum na řádku „splatnost / zaplaťte do…“ nebo hned pod ním
        var due: LocalDate? = null
        for ((i, line) in lines.withIndex()) {
            if (!dueLineRe.containsMatchIn(line)) continue
            val d = (i..minOf(i + 2, lines.lastIndex)).asSequence().mapNotNull { j -> datesIn(lines[j], today).firstOrNull() }.firstOrNull()
            if (d != null && d.isAfter(today.minusYears(1)) && d.isBefore(today.plusYears(2))) { due = d; break }
        }

        val accounts = accountRe.findAll(text)
            .filter { it.groupValues[3] in bankCodes }
            .map { m ->
                val pre = m.groupValues[1].takeIf { it.isNotEmpty() }
                val txt = (pre?.let { "$it-" } ?: "") + m.groupValues[2] + "/" + m.groupValues[3]
                Account(txt, accountValid(pre, m.groupValues[2]))
            }.distinctBy { it.text }.toList()
        val ibans = ibanRe.findAll(text).map { it.groupValues[1].replace(" ", "") }.filter { ibanValid(it) }.distinct().toList()
        val vs = vsRe.find(text)?.groupValues?.get(1)
        val sites = ScamCheck.sites(text)
        val phones = phoneRe.findAll(text).map { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            .filter { it.first() in "2345679" }.toSet()

        // ---------- Je to pravé? ----------
        val scam = mutableListOf<String>()
        val warn = mutableListOf<String>()
        if (cardRe.containsMatchIn(text)) scam += "Chce číslo karty, PIN nebo heslo. To úřady ani firmy dopisem nikdy nechtějí."
        if (cryptoRe.containsMatchIn(text)) scam += "Chce zaplatit bitcoiny, kupónem nebo dárkovou kartou. Tak platí jen podvodníci."
        if (prizeFeeRe.containsMatchIn(text) && kind != Kind.PENSION) scam += "Slibuje výhru, ale napřed chce zaplatit. To je podvod."
        val foreign = ibans.filterNot { it.startsWith("CZ") }
        if (foreign.isNotEmpty() && k != null) scam += "Tváří se jako ${k.name}, ale peníze mají jít na účet v cizině."
        else if (foreign.isNotEmpty() && kind != Kind.AD) warn += "Peníze mají jít na účet v cizině."
        val official = k?.sites ?: emptySet()
        val foreignSites = sites.filterNot { it in official }
        if (k != null && official.isNotEmpty() && foreignSites.isNotEmpty() && (kind == Kind.PAY || kind == Kind.DEBT))
            warn += "Tváří se jako ${k.name}, ale odkaz vede na cizí stránku ${foreignSites.first()}."
        if (accounts.isNotEmpty() && accounts.none { it.valid } && ibans.none { it.startsWith("CZ") })
            warn += "Číslo účtu se mi nepodařilo ověřit. Možná jsem ho špatně přečetla, možná je falešné."
        if (threatRe.containsMatchIn(text) && kind != Kind.COURT && k?.name != "Policie ČR")
            warn += "Vyhrožuje zatčením, odpojením nebo zabavením. Tím podvodníci straší."
        if (urgentRe.containsMatchIn(text) && kind in setOf(Kind.PAY, Kind.DEBT)) warn += "Tlačí ke spěchu, abyste nestihli přemýšlet."
        if (kind == Kind.EXEKUCE) warn += "Exekuce je vážná věc. Ať se na dopis hned podívá rodina."
        if (kind == Kind.COURT) warn += "Dopis od soudu má krátké lhůty. Ať se na něj hned podívá rodina."
        if (k == null && (kind == Kind.PAY || kind == Kind.DEBT)) warn += "Chce peníze a odesílatele neznám."

        val level = when {
            scam.isNotEmpty() -> ScamCheck.Level.SCAM
            warn.isNotEmpty() -> ScamCheck.Level.WARN
            else -> ScamCheck.Level.OK
        }
        val reasons = (scam + warn).ifEmpty {
            listOf(when {
                k != null -> "${capital(k.name)} je známý odesílatel a nic podezřelého jsem nenašla."
                else -> "Nechce peníze, karty ani hesla."
            })
        }
        return Letter(sender, k != null, kind, what, amount, due, accounts, ibans, vs, sites, phones, level, reasons, text.trim().length)
    }

    /** Odesílatel podle hlavičky: řádek s „s.r.o.“, „a.s.“, „úřad“… nebo první řádek. */
    private fun guessSender(lines: List<String>): String? {
        val firm = Regex("(s\\.\\s?r\\.\\s?o\\.|a\\.\\s?s\\.|spol\\.|úřad|urad|z\\.\\s?s\\.|o\\.\\s?p\\.\\s?s\\.|družstvo|bank)", RegexOption.IGNORE_CASE)
        return lines.take(15).firstOrNull { firm.containsMatchIn(it) && it.length in 4..60 }
            ?.replace(Regex(",?\\s*(IČO?|DIČ).*$", RegexOption.IGNORE_CASE), "")?.trim()?.trimEnd(',')
    }

    // ---------- Věty pro seniora ----------

    fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

    fun money(halere: Long): String {
        val kc = halere / 100; val h = halere % 100
        val grouped = kc.toString().reversed().chunked(3).joinToString(" ").reversed()
        val unit = when { kc == 1L -> "koruna"; kc in 2..4 -> "koruny"; else -> "korun" }
        return if (h == 0L) "$grouped $unit" else "$grouped $unit $h haléřů"
    }

    fun date(d: LocalDate) = "${d.dayOfMonth}. ${months[d.monthValue - 1]}"

    /** 1. věta: o co jde. */
    fun about(l: Letter): String {
        val who = l.sender?.let { capital(it) }
        val sum = l.amount?.let { " " + money(it) } ?: ""
        val subject = who ?: "Dopis"
        return when (l.kind) {
            Kind.PAY -> if (l.what != null) "$subject chce zaplatit ${l.what}$sum." else
                if (who != null) "$who chce zaplatit$sum." else "Dopis chce zaplatit$sum."
            Kind.DEBT -> "${who ?: "Někdo"} upomíná, že je potřeba doplatit${l.what?.let { " $it" } ?: ""}$sum."
            Kind.EXEKUCE -> "Je to dopis o exekuci" + (if (l.amount != null) " na$sum." else ".")
            Kind.COURT -> "Je to dopis od soudu."
            Kind.REFUND -> "${who ?: "Někdo"} vám vrací přeplatek$sum."
            Kind.PENSION -> "${who ?: "Dopis"} ${if (who != null) "vám píše o důchodu" else "je o důchodu"}."
            Kind.AD -> if (who != null) "$who vám posílá reklamu." else "Je to reklama."
            Kind.INFO -> if (who != null) "$who vám posílá informaci." else "Je to informační dopis."
        }
    }

    /** 2. věta: je to pravé? */
    fun verdict(l: Letter): String = when (l.level) {
        ScamCheck.Level.SCAM -> "Pozor, vypadá to jako podvod."
        ScamCheck.Level.WARN -> "Nejsem si jistá, jestli je to pravé."
        ScamCheck.Level.OK -> "Vypadá to pravé."
    }

    /** 3. věta: co udělat a do kdy. */
    fun todo(l: Letter, today: LocalDate = LocalDate.now()): String = when {
        l.level == ScamCheck.Level.SCAM -> "Nic neplaťte a nikam nevolejte."
        l.kind == Kind.EXEKUCE || l.kind == Kind.COURT -> "Nic neplaťte a ještě dnes ho ukažte rodině."
        l.level == ScamCheck.Level.WARN && l.wantsMoney -> "Zatím nic neplaťte, ať se podívá rodina."
        l.wantsMoney && l.due != null && l.due.isBefore(today) -> "Zaplaťte co nejdřív, termín byl už ${date(l.due)}."
        l.wantsMoney && l.due != null && l.due == today -> "Zaplaťte ještě dnes."
        l.wantsMoney && l.due != null -> "Zaplaťte do ${date(l.due)}."
        l.wantsMoney -> "Zaplaťte. Termín jsem nenašla, zeptejte se rodiny."
        l.kind == Kind.REFUND -> "Nic neplaťte, peníze vám přijdou samy."
        l.kind == Kind.AD -> "Nic nedělejte, je to jen reklama."
        else -> "Nic nedělejte, je to jen informace."
    }

    /** Smí se připomenout termín? */
    fun canRemind(l: Letter, today: LocalDate = LocalDate.now()) =
        l.level == ScamCheck.Level.OK && l.wantsMoney && l.due != null && l.due.isAfter(today)

    /** Více: nejvýš 5 krátkých vět. */
    fun more(l: Letter): List<String> = listOfNotNull(
        l.sender?.let { "Odesílatel: ${capital(it).trimEnd('.')}." },
        l.amount?.let { "Částka: ${money(it)}." },
        (l.accounts.firstOrNull()?.text ?: l.ibans.firstOrNull())?.let { acc ->
            "Účet: $acc" + (l.vs?.let { ", variabilní symbol $it." } ?: ".")
        } ?: l.vs?.let { "Variabilní symbol: $it." },
        l.due?.let { "Termín: ${date(it)} ${it.year}." },
        l.reasons.first(),
    ).take(5)

    /** SMS pro rodinu: všechno důležité na jednom místě. */
    fun familyText(l: Letter, senior: String, male: Boolean, today: LocalDate = LocalDate.now()): String {
        val parts = mutableListOf<String>()
        val took = if (male) "vyfotil" else "vyfotila"
        parts += when (l.level) {
            ScamCheck.Level.SCAM -> "Postaráno: $senior $took PODEZŘELÝ dopis."
            ScamCheck.Level.WARN -> "Postaráno: $senior $took dopis, podívejte se na něj."
            ScamCheck.Level.OK -> "Postaráno: $senior $took dopis."
        }
        parts += about(l)
        l.due?.let { parts += "Termín: ${date(it)} ${it.year}." }
        (l.accounts.firstOrNull()?.text ?: l.ibans.firstOrNull())?.let { parts += "Účet $it" + (l.vs?.let { v -> ", VS $v." } ?: ".") }
        parts += l.reasons.first()
        parts += "Řekla jsem ${if (male) "mu" else "jí"}: ${todo(l, today)}"
        return parts.joinToString(" ")
    }
}
