package cz.druhynazor

import android.content.Context

/** Jedna otázka. Správná odpověď je v seznamu vždy první, na obrazovce se zamíchá. */
data class QuizQuestion(val text: String, val answers: List<String>) {
    val correct get() = answers[0]
}

/**
 * Hádanka dne: každý den 3 otázky pro radost (přísloví, hádanky, co nepatří, Česko, počítání).
 * Nikdy „špatně“, jen „Skoro, správně je…“. Nejde o test paměti a nic neslibuje o zdraví.
 * Otázky se neopakují, dokud se neprojdou všechny.
 */
object Quiz {
    const val ID = "hd"
    const val PER_DAY = 3

    private fun q(text: String, vararg a: String) = QuizQuestion(text, a.toList())

    val BANK: List<QuizQuestion> = listOf(
        // Přísloví
        q("Doplňte přísloví: Kdo jinému jámu kopá…", "sám do ní padá", "ten má lopatu", "ten se nadře"),
        q("Doplňte přísloví: Bez práce…", "nejsou koláče", "není zábava", "není oběd"),
        q("Doplňte přísloví: Ranní ptáče…", "dál doskáče", "nejvíc zpívá", "brzy usíná"),
        q("Doplňte přísloví: Komu se nelení…", "tomu se zelení", "ten má peníze", "ten dlouho spí"),
        q("Doplňte přísloví: Lepší vrabec v hrsti…", "než holub na střeše", "než kos na plotě", "než slepice v kurníku"),
        q("Doplňte přísloví: Darovanému koni…", "na zuby nehleď", "dej oves", "nesedej na hřbet"),
        q("Doplňte přísloví: Kdo se moc ptá…", "moc se dozví", "ten se nudí", "ten nic neví"),
        q("Doplňte přísloví: Co můžeš udělat dnes…", "neodkládej na zítřek", "udělej pozítří", "nech na neděli"),
        q("Doplňte přísloví: Jablko nepadá daleko…", "od stromu", "od plotu", "od zahrady"),
        q("Doplňte přísloví: Tichá voda…", "břehy mele", "je studená", "nic nedělá"),
        q("Doplňte přísloví: Mluviti stříbro…", "mlčeti zlato", "zpívati měď", "psáti železo"),
        q("Doplňte přísloví: Všude dobře…", "doma nejlépe", "venku hůř", "u babičky nejlíp"),
        q("Doplňte přísloví: Kdo šetří…", "má za tři", "ten nic nemá", "ten je smutný"),
        q("Doplňte přísloví: Opakování…", "matka moudrosti", "je nuda", "dělá mistra"),
        q("Doplňte přísloví: Kdo dřív přijde…", "ten dřív mele", "ten čeká", "ten odchází"),
        q("Doplňte přísloví: Jak se do lesa volá…", "tak se z lesa ozývá", "tak se v lese spí", "tak se jde domů"),
        q("Doplňte přísloví: Kdo nic nedělá…", "nic nezkazí", "ten se nudí", "ten má čas"),
        q("Doplňte přísloví: Hlad je…", "nejlepší kuchař", "zlý pán", "dlouhý den"),
        q("Doplňte přísloví: Kdo chce psa bít…", "hůl si vždycky najde", "musí ho chytit", "ten ho nemá rád"),
        q("Doplňte přísloví: Sejde z očí…", "sejde z mysli", "sejde ze schodů", "sejde z cesty"),
        q("Doplňte přísloví: Ráno moudřejší…", "večera", "noci", "oběda"),
        q("Doplňte přísloví: Kde se dva perou…", "třetí se směje", "tam je hluk", "nikdo nevyhraje"),
        q("Doplňte přísloví: Na hrubý pytel…", "hrubá záplata", "velká jehla", "silná nit"),
        q("Doplňte přísloví: Lež má…", "krátké nohy", "dlouhé vlasy", "velké oči"),
        q("Doplňte přísloví: Dvakrát měř…", "jednou řež", "třikrát šij", "pak odpočívej"),
        q("Doplňte přísloví: Vrána k vráně…", "sedá", "letí", "volá"),
        q("Doplňte přísloví: Trpělivost…", "růže přináší", "je těžká", "trvá dlouho"),
        q("Doplňte přísloví: Mýlit se je…", "lidské", "hloupé", "zakázané"),

        // Hádanky
        q("Hádanka: Má zuby, a nekouše. Co je to?", "hřeben", "lžíce", "kniha"),
        q("Hádanka: Čím víc z ní berete, tím je větší. Co je to?", "jáma", "hrnec", "kapsa"),
        q("Hádanka: Má ručičky, a nemá ruce. Co je to?", "hodiny", "rukavice", "strom"),
        q("Hádanka: Má krk, a nemá hlavu. Co je to?", "láhev", "šála", "židle"),
        q("Hádanka: Běží, a nemá nohy. Co je to?", "řeka", "kámen", "dům"),
        q("Hádanka: Patří vám, ale ostatní to používají víc než vy. Co je to?", "vaše jméno", "vaše boty", "vaše lžíce"),
        q("Hádanka: Má očka, a nevidí. Co je to?", "brambora", "mrkev", "jablko"),

        // Co nepatří mezi ostatní
        q("Co nepatří mezi ovoce?", "mrkev", "jablko", "hruška"),
        q("Co nepatří mezi stromy?", "kopřiva", "dub", "buk"),
        q("Co nepatří mezi ptáky?", "netopýr", "vrabec", "sýkora"),
        q("Co nepatří mezi hudební nástroje?", "vařečka", "housle", "harmonika"),
        q("Co nepatří mezi měsíce?", "středa", "květen", "září"),
        q("Co nepatří mezi zeleninu?", "švestka", "mrkev", "celer"),
        q("Co nepatří mezi houby?", "sedmikráska", "hřib", "bedla"),
        q("Co nepatří mezi savce?", "kapr", "kočka", "kůň"),

        // Česko a svět
        q("Jak se jmenuje hlavní město Slovenska?", "Bratislava", "Košice", "Žilina"),
        q("Která hora je nejvyšší v Česku?", "Sněžka", "Praděd", "Lysá hora"),
        q("Která řeka je nejdelší z těch, co tečou jen po Česku?", "Vltava", "Labe", "Morava"),
        q("Kdo napsal knihu Babička?", "Božena Němcová", "Karolina Světlá", "Jan Neruda"),
        q("Kdo složil Mou vlast?", "Bedřich Smetana", "Antonín Dvořák", "Leoš Janáček"),
        q("Kdo napsal Osudy dobrého vojáka Švejka?", "Jaroslav Hašek", "Karel Čapek", "Bohumil Hrabal"),
        q("Kdo nakreslil obrázky ke Švejkovi?", "Josef Lada", "Zdeněk Miler", "Jiří Trnka"),
        q("Kdo vymyslel a nakreslil Krtečka?", "Zdeněk Miler", "Josef Lada", "Radek Pilař"),
        q("Kdo napsal Povídání o pejskovi a kočičce?", "Josef Čapek", "Jan Werich", "Václav Čtvrtek"),
        q("Ve kterém městě je Karlův most?", "v Praze", "v Brně", "v Českém Krumlově"),
        q("Které zvíře je ve znaku České republiky?", "lev", "orel", "medvěd"),
        q("Jakou barvu má klín na české vlajce?", "modrou", "zelenou", "žlutou"),
        q("Kdo byl první prezident Československa?", "Tomáš Garrigue Masaryk", "Edvard Beneš", "Václav Havel"),
        q("Který prezident psal předtím divadelní hry?", "Václav Havel", "Ludvík Svoboda", "Antonín Zápotocký"),
        q("Ve kterém roce byla sametová revoluce?", "1989", "1968", "1993"),
        q("Ve kterém roce vstoupilo Česko do Evropské unie?", "2004", "1999", "2010"),
        q("Co si připomínáme 28. října?", "vznik samostatného Československa", "konec války", "Den matek"),
        q("Která řeka teče Brnem?", "Svratka", "Vltava", "Ohře"),
        q("Které město je známé perníkem?", "Pardubice", "Kladno", "Most"),
        q("Olomoucké tvarůžky jsou…", "sýr", "pečivo", "uzenina"),
        q("Ze kterého ovoce se pálí slivovice?", "ze švestek", "z jablek", "z hrušek"),
        q("Která rostlina dává pivu hořkou chuť?", "chmel", "mák", "len"),
        q("Kdo v pohádce Tři oříšky pro Popelku přinesl Popelce oříšky?", "Vincek", "kominík", "princ"),
        q("Kdo bydlí v pařezové chaloupce?", "Křemílek a Vochomůrka", "Pat a Mat", "Bob a Bobek"),
        q("Kteří dva kutilové z večerníčku všechno pokazí?", "Pat a Mat", "Křemílek a Vochomůrka", "Maxipes Fík a Ája"),

        // Svátky a rok
        q("Kdy je Štědrý den?", "24. prosince", "25. prosince", "31. prosince"),
        q("Kdy má svátek Mikuláš?", "6. prosince", "1. prosince", "24. prosince"),
        q("Kolik svíček je na adventním věnci?", "4", "3", "7"),
        q("Jaká ryba se v Česku tradičně jí na Vánoce?", "kapr", "pstruh", "sardinka"),
        q("Co se o Velikonocích tradičně zdobí?", "vajíčka", "brambory", "ořechy"),
        q("Který měsíc má nejméně dní?", "únor", "duben", "listopad"),
        q("Kolik dní má přestupný rok?", "366", "365", "364"),
        q("Ve kterém měsíci začíná jaro?", "v březnu", "v únoru", "v dubnu"),
        q("Kolik je ročních období?", "4", "3", "5"),

        // Příroda
        q("Kolik nohou má pavouk?", "8", "6", "10"),
        q("Kolik nohou má hmyz, třeba moucha?", "6", "8", "4"),
        q("Který pták podle pověsti nosí děti?", "čáp", "vrána", "labuť"),
        q("Které zvíře je největší na světě?", "plejtvák obrovský, to je velryba", "slon", "žirafa"),
        q("Jakou barvu má smaragd?", "zelenou", "červenou", "modrou"),
        q("Která planeta je nejblíž Slunci?", "Merkur", "Mars", "Země"),
        q("Jak se jmenuje naše galaxie?", "Mléčná dráha", "Velký vůz", "Polárka"),

        // Počítání
        q("Kolik je sedm krát osm?", "56", "54", "64"),
        q("Kolik je patnáct plus dvacet sedm?", "42", "32", "44"),
        q("Kolik je polovina ze sto padesáti?", "75", "70", "85"),
        q("Kolik korun je deset padesátikorun?", "500", "50", "1000"),
        q("Kolik je sto mínus třicet sedm?", "63", "73", "67"),
        q("Kolik kusů je kopa vajec?", "60", "12", "30"),
        q("Kolik kusů je tucet?", "12", "10", "20"),
        q("Kolik minut je čtvrt hodiny?", "15", "20", "25"),
        q("Kolik je dvakrát dvacet pět?", "50", "45", "52"),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("hadanka", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = Care.enabled(ctx, ID)

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Pořadí otázek: jednou zamíchané, pak se jde postupně dokola. */
    private fun order(ctx: Context): List<Int> {
        val p = prefs(ctx)
        val saved = p.getString("poradi", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        if (saved != null && saved.size == BANK.size && saved.toSet().size == BANK.size) return saved
        val fresh = BANK.indices.shuffled()
        p.edit().putString("poradi", fresh.joinToString(",")).putInt("dalsi", 0).apply()
        return fresh
    }

    /** Další otázka v pořadí (po projití všech znovu od začátku). */
    fun next(ctx: Context): QuizQuestion {
        val p = prefs(ctx)
        val o = order(ctx)
        val i = p.getInt("dalsi", 0)
        p.edit().putInt("dalsi", (i + 1) % o.size).apply()
        return BANK[o[i % o.size]]
    }

    fun answeredToday(ctx: Context): Int = prefs(ctx).let { if (it.getString("den", "") == today()) it.getInt("odpovedi", 0) else 0 }
    fun rightToday(ctx: Context): Int = prefs(ctx).let { if (it.getString("den", "") == today()) it.getInt("spravne", 0) else 0 }
    fun doneToday(ctx: Context) = answeredToday(ctx) >= PER_DAY

    fun record(ctx: Context, right: Boolean) {
        val p = prefs(ctx)
        val d = today()
        val same = p.getString("den", "") == d
        val answered = (if (same) p.getInt("odpovedi", 0) else 0) + 1
        val good = (if (same) p.getInt("spravne", 0) else 0) + if (right) 1 else 0
        p.edit().putString("den", d).putInt("odpovedi", answered).putInt("spravne", good)
            .putLong("naposledy", System.currentTimeMillis()).apply()
    }

    /** Kdy senior naposledy hrál (pro rodinu v Pro rodinu). */
    fun lastPlayed(ctx: Context) = prefs(ctx).getLong("naposledy", 0L)
}
