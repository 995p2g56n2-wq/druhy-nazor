package cz.druhynazor

import android.app.role.RoleManager
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Jeden pomocník (podaplikace) v katalogu Postaráno. */
data class Module(
    val id: String,
    val name: String,
    val icon: String,
    /** Jedna věta na kartičce: co uvidí senior. */
    val desc: String,
    /** Pomocník je opravdu hotový. Jen hotové senior vidí a jsou zapnuté samy. */
    val ready: Boolean,
    /** Schovaný pod odkazem „Další pomocníci“, aby úvodní nabídka nebyla přeplněná. */
    val more: Boolean,
    val co: String,
    val jak: List<String>,
    val rodina: String,
    val bezpeci: String,
    val pozn: String? = null,
)

/** Seznam pomocníků. Hotové jsou zapnuté samy, nehotové se nikde neukazují. */
object Modules {
    const val DN = "dn"
    const val DOPIS = "do"

    val all = listOf(
        Module(DN, "Druhý názor", "🛡️",
            "Pozná podezřelý hovor nebo SMS. Hned vás varuje a dá vědět rodině.", ready = true, more = false,
            co = "Hlídá hovory a SMS za vás. Když přijde podvod, hned vás varuje a dá vědět rodině. Na nic se nemusíte ptát.",
            jak = listOf(
                "Někdo vám zavolá nebo vám přijde SMS.",
                "U hovoru aplikace zkontroluje číslo dřív, než telefon zazvoní. U SMS přečte text a pozná podvodný odkaz, výhrůžku nebo nátlak.",
                "Když je to podezřelé, objeví se velké červené varování a hlas řekne, co dělat.",
                "Rodina hned dostane zprávu, aby vám mohla zavolat a uklidnit vás."),
            rodina = "Jen zprávu, že přišel podezřelý hovor nebo SMS. Vaše běžné zprávy nevidí nikdo.",
            bezpeci = "Aplikace vaše SMS nikam neposílá. Zkontroluje jen to nutné přímo v telefonu."),
        Module(DOPIS, "Dopisy", "📄",
            "Namiřte telefon na dopis. Aplikace nahlas řekne, o co jde, jestli je pravý a co udělat.", ready = true, more = false,
            co = "Přečte dopis za vás a řekne třemi větami, o co jde, jestli je pravý a co udělat do kdy.",
            jak = listOf(
                "Ťuknete na Dopis a namíříte telefon na papír. Vyfotí se sám.",
                "Aplikace dopis přečte a nahlas řekne, o co jde, jestli je pravý a co udělat.",
                "Termín vám připomene a fotku s shrnutím pošle rodině."),
            rodina = "Fotku dopisu a shrnutí, když na Poslat rodině ťuknete vy, nebo když je dopis podvod.",
            bezpeci = "Dopis se čte přímo v telefonu, nikam se neposílá. Aplikace nikdy sama neplatí."),
        Module("en", "Energie", "⚡",
            "Vyfoťte fakturu nebo elektroměr. Aplikace lidsky vysvětlí, co platíte a jak ušetřit.", ready = false, more = false,
            co = "Vysvětlí fakturu za elektřinu tak, aby jí rozuměl každý, a poradí, jak ušetřit.",
            jak = listOf(
                "Vyfotíte vyúčtování.",
                "Aplikace ho přečte a jednou větou řekne, kolik platíte a proč.",
                "Porovná ho s loňským a poradí, co udělat. Někdy je nejlepší rada nic neměnit.",
                "Když chcete, jednou týdně připomene vyfotit elektroměr nebo vodoměr a stav si zapíše sama."),
            rodina = "Shrnutí faktury a upozornění na důležité termíny, třeba konec smlouvy.",
            bezpeci = "Fotku po přečtení smaže. Radí nezávisle a nic vám nevnucuje."),
        Module("te", "Pomoc s technikou", "📺",
            "Nevíte si rady s telefonem, televizí nebo Wi-Fi? Zeptejte se hlasem a dostanete radu krok za krokem.", ready = false, more = false,
            co = "Poradí s telefonem, televizí, Wi-Fi nebo tiskárnou. Trpělivě a tolikrát, kolikrát budete chtít.",
            jak = listOf(
                "Zeptáte se hlasem, třeba: „Jak zvětším písmo?“",
                "Aplikace odpoví krok za krokem a každý krok přečte nahlas.",
                "Když to nepůjde, nabídne zavolat někomu z rodiny."),
            rodina = "Nic, dokud sami nezavoláte o pomoc.",
            bezpeci = "Aplikace po vás nikdy nebude chtít hesla ani čísla karet. Kdo o ně žádá, je podvodník."),
        Module("tr", "Trénink proti podvodům", "🎯",
            "Občas pošle cvičný podvod, abyste ten skutečný hned poznali.", ready = false, more = true,
            co = "Naučí vás poznat podvod dřív, než přijde ten skutečný.",
            jak = listOf(
                "Jednou za čas vám přijde cvičná podvodná zpráva.",
                "Když naletíte, nic se nestane. Aplikace vám v klidu vysvětlí, podle čeho se to dalo poznat."),
            rodina = "Jak vám trénink jde.",
            bezpeci = "Cvičná zpráva nikdy nevede na skutečný odkaz a nic nestojí."),
        Module("ht", "Hlídač termínů", "📅",
            "Pohlídá, kdy co zaplatit a kdy končí smlouvy, aby nic nepropadlo.", ready = false, more = true,
            co = "Hlídá, kdy co zaplatit a kdy končí smlouvy, aby nic nepropadlo.",
            jak = listOf(
                "Termíny si vezme z faktur, které vyfotíte, nebo je zadá rodina.",
                "Včas připomene třeba konec smlouvy nebo změnu záloh."),
            rodina = "Stejné připomínky, aby mohla pomoct.",
            bezpeci = "Nic za vás neplatí, jen připomíná."),
        Module("ok", "Jsem v pořádku", "❤️",
            "Jedním tlačítkem dáte rodině vědět, že jste v pořádku, nebo zavoláte o pomoc.", ready = false, more = true,
            co = "Jedno tlačítko pro klid celé rodiny.",
            jak = listOf(
                "Každý den jedním ťuknutím dáte vědět: „Jsem v pořádku.“",
                "Velké tlačítko „Potřebuji pomoc“ pošle rodině zprávu i s místem, kde jste."),
            rodina = "Že jste v pořádku. Polohu jen tehdy, když voláte o pomoc.",
            bezpeci = "Nikdo vás nesleduje. Poloha se odešle jen po zmáčknutí tlačítka."),
        Module("hp", "Hlídač peněz", "💰",
            "Pohlídá váš účet. Když odejde neobvyklá platba, dá vědět vám i rodině.", ready = false, more = true,
            co = "Hlídá bankovní účet a varuje před neobvyklou platbou.",
            jak = listOf(
                "Ve svém internetovém bankovnictví povolíte jen čtení výpisu.",
                "Když odejde něco neobvyklého, varuje vás i rodinu."),
            rodina = "Jen upozornění na neobvyklé platby, ne celý výpis.",
            bezpeci = "K penězům se aplikace nedostane, vidí jen výpis. Heslo do banky nikdy nechce."),
    )

    fun byId(id: String) = all.first { it.id == id }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("postarano", Context.MODE_PRIVATE)

    /**
     * Zapnutí pomocníci. Katalog ani instalování už není: co je hotové, je zapnuté samo.
     * Nehotové pomocníky senior nevidí. Až bude některý hotový, objeví se po aktualizaci sám.
     */
    fun installed(ctx: Context): Set<String> = all.filter { it.ready }.map { it.id }.toSet()

    /** Uživatel už prošel úvodem (rodina a jak mu říkají). Starší verze ukládaly seznam pomocníků. */
    fun onboarded(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("onboarded", false) || !p.getStringSet("installed", null).isNullOrEmpty() ||
            oldDruhyNazorUser(ctx)
    }
    fun setOnboarded(ctx: Context) = prefs(ctx).edit().putBoolean("onboarded", true).apply()

    private fun oldDruhyNazorUser(ctx: Context): Boolean {
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_SMS) && rm.isRoleHeld(RoleManager.ROLE_SMS)
    }
}

/** Čte texty nahlas česky. */
class Speaker(private val ctx: Context) {
    // Jeden hlas na celou obrazovku: rozjíždí se jen jednou, pak mluví hned bez čekání.
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(text: String) {
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "postarano"); return }
        pending = text
        if (t != null) return
        tts = Voice.tts(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale("cs", "CZ")
                tts?.setSpeechRate(0.9f)
                pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "postarano") }
                pending = null
            }
        }
    }

    /** Přestat mluvit (hlas zůstane připravený). */
    fun stop() { pending = null; tts?.stop() }

    fun shutdown() { pending = null; tts?.shutdown(); tts = null; ready = false }
}
