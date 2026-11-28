package cz.druhynazor

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView

/**
 * Podrobné vysvětlení a Zásady ochrany osobních údajů (čl. 13 GDPR).
 * Otevírá se z obrazovky „Než začneme“ (před souhlasem) a z Pro rodinu.
 * Text je stejný jako návrh Zásad v dokumentu projektu. Místa [doplníme] čekají na údaje správce.
 */
class PolicyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.column(this, 20, 14).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "Podrobné vysvětlení a Zásady ochrany osobních údajů", 26f, bold = true))
        root.addView(Ui.text(this, "Až si to přečtete, ťukněte úplně dole na Zpět.", 18f, color = Ui.MUTED))
        for ((head, body) in SECTIONS) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, head, 21f, bold = true))
            card.addView(Ui.text(this, body, 18f))
            root.addView(card)
        }
        root.addView(Ui.button(this, "Zpět", sp = 22f) { finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    companion object {
        val SECTIONS = listOf(
            "1. Kdo za údaje odpovídá" to
                "Správcem osobních údajů je [doplníme: jméno nebo firma, adresa, IČO]. " +
                "Dotazy, žádosti a stížnosti posílejte na [doplníme: e-mail]. Odpovíme nejpozději do 30 dnů.\n\n" +
                "Řídíme se nařízením Evropského parlamentu a Rady (EU) 2016/679 (obecné nařízení o ochraně osobních údajů, GDPR) " +
                "a zákonem č. 110/2019 Sb., o zpracování osobních údajů.",

            "2. Co zůstává jen ve vašem telefonu" to
                "Postaráno chrání před podvody. Kontroluje SMS, příchozí hovory, zprávy z WhatsAppu a dopisy, které vyfotíte, " +
                "a při podezření varuje vás a vaši rodinu.\n\n" +
                "Tohle všechno se vyhodnocuje jen ve vašem telefonu a nikam se neposílá:\n" +
                "•  obsah SMS a MMS (Postaráno je vaše výchozí aplikace na zprávy, zprávy zůstávají v telefonu, dokud je nesmažete),\n" +
                "•  čísla příchozích hovorů,\n" +
                "•  odesílatel a text oznámení z WhatsAppu a podobných aplikací,\n" +
                "•  fotky dopisů (text z fotky čte nástroj Google ML Kit přímo v telefonu, fotka se smaže po 60 dnech),\n" +
                "•  text dopisů, pokud jste nezapnuli čtení umělou inteligencí (viz bod 4),\n" +
                "•  jména a čísla lidí z rodiny, které jste vybrali, a jejich rozhodnutí o zprávách,\n" +
                "•  připomínky plateb,\n" +
                "•  léky a časy připomínek, které zadala rodina, a jestli jste lék potvrdili,\n" +
                "•  odpovědi na otázku Jak se máte.\n\n" +
                "Polohu telefon zjišťuje jen ve chvíli, kdy zmáčknete Pomoc. Jinak ji nesleduje ani neukládá.\n\n" +
                "Tyto údaje nevidíme my ani nikdo jiný. Smažete je odinstalováním.",

            "3. Upozornění rodině (SMS a MMS)" to
                "Co: text podezřelé zprávy, číslo odesílatele, shrnutí nebo fotka dopisu. " +
                "Dále: název léku, když ho do hodiny nepotvrdíte (to je údaj o zdraví), odpověď Není mi dobře " +
                "nebo to, že jste do určené doby neodpověděli na Jak se máte, a po zmáčknutí Pomoc odkaz na mapu s místem, kde jste.\n" +
                "Komu: jen lidem z rodiny, které jste vybrali, přes vašeho mobilního operátora.\n" +
                "Proč: aby vám rodina mohla poradit.\n" +
                "Jak dlouho: v telefonech rodiny, dokud zprávu nesmažou.\n" +
                "Právní důvod: váš souhlas (čl. 6 odst. 1 písm. a) GDPR), u léků výslovný souhlas (čl. 9 odst. 2 písm. a) GDPR). " +
                "Souhlas můžete kdykoli odvolat odinstalováním, odebráním lidí z rodiny nebo vypnutím Léků, " +
                "Jsem v pořádku a Pomoci v části Pro rodinu.",

            "4. Čtení dopisů umělou inteligencí" to
                "Zapíná se jen s vaším souhlasem při prvním otevření Dopisu. Bez souhlasu se dopis čte jen v telefonu.\n" +
                "Co: text vyfoceného dopisu, jak ho přečetl telefon. Fotka se neposílá. Rodná čísla telefon před odesláním " +
                "nahradí slovy [rodné číslo]. Dopis může obsahovat i údaje o zdraví (třeba od pojišťovny).\n" +
                "Komu: přes náš server u Cloudflare (Cloudflare Inc., USA), který nic neukládá, ke společnosti Anthropic " +
                "(Anthropic PBC, USA), která text přečte umělou inteligencí Claude a vrátí shrnutí. " +
                "Telefon pak každý údaj zkontroluje s dopisem.\n" +
                "Proč: aby čtení dopisů bylo přesnější.\n" +
                "Jak dlouho: u nás nikde. U společnosti Anthropic [doplníme po ověření podmínek]. " +
                "Anthropic podle svých podmínek tyto texty nepoužívá k učení umělé inteligence [ověříme].\n" +
                "Právní důvod: váš výslovný souhlas (čl. 6 odst. 1 písm. a) a čl. 9 odst. 2 písm. a) GDPR).\n\n" +
                "Souhlas můžete kdykoli odvolat v části Pro rodinu, Čtení dopisů, volba Jen v telefonu.",

            "5. Společný seznam podvodníků" to
                "Co: když rodina označí zprávu jako podvod, pošle se číslo, ze kterého přišla, adresy podvodných webů " +
                "a náhodné označení vašeho telefonu. Vaše jméno, vaše číslo ani text zprávy se neposílají. " +
                "Čísla lidí z vaší rodiny se nikdy nehlásí.\n" +
                "Komu: hlášení jde přes schránku na ntfy.sh (ntfy LLC, USA), kde leží 12 hodin, a pak na GitHub " +
                "(GitHub Inc., skupina Microsoft, USA), kde se z hlášení skládá veřejný seznam.\n" +
                "Proč: aby varování chránilo i ostatní lidi.\n" +
                "Jak dlouho: 90 dní od posledního hlášení. Číslo se zveřejní, až když ho nahlásí aspoň 3 různé telefony. " +
                "Do té doby se ukládá jen jako nevratně zakódovaný otisk.\n" +
                "Právní důvod: oprávněný zájem na ochraně lidí před podvody (čl. 6 odst. 1 písm. f) GDPR).\n\n" +
                "Hlášení můžete vypnout v části Pro rodinu, vypínač Hlásit podvodníky do společného seznamu.\n\n" +
                "Jednou za hodinu si telefon seznam stahuje z GitHubu. GitHub přitom vidí IP adresu telefonu.",

            "6. Fotky a vzkazy od rodiny, počasí" to
                "Co: fotky, krátká videa, hlasové a psané vzkazy, které vám pošle rodina přes svůj odkaz, jméno toho, kdo je poslal, " +
                "a jestli jste je viděli nebo poslali srdíčko. Dále jména lidí z rodiny a to, jak vám říkají (třeba Babička). " +
                "Vaše telefonní číslo ani čísla rodiny se na server neposílají.\n" +
                "Komu: na náš server u Cloudflare (Cloudflare Inc., USA). Vidí to jen lidé z rodiny, kteří mají odkaz.\n" +
                "Proč: aby vám rodina mohla posílat fotky, videa a vzkazy.\n" +
                "Jak dlouho: 30 dní, pak se samy smažou, na serveru i v telefonu. Ve schránce je nejvýš 40 věcí.\n" +
                "Právní důvod: váš souhlas (čl. 6 odst. 1 písm. a) GDPR).\n\n" +
                "Vypnout jde v části Pro rodinu, vypínač Zapnout Od rodiny." +
                "\n\nPočasí: pro předpověď se posílá jen přibližné místo (zaokrouhlené asi na 1 km) nebo název obce, " +
                "kterou zadala rodina, službě Open-Meteo. Jméno ani číslo se neposílá. Vypnout jde vypínačem Hlásit počasí.\n" +
                "Hádanka dne, Na co mám nárok a Rádio nic nikam neposílají (Rádio jen otevře aplikaci Českého rozhlasu, " +
                "Na co mám nárok pošle rodině SMS, jen když ťuknete na Poslat rodině).",

            "7. Číslo v seznamu omylem" to
                "Pokud se vaše číslo dostalo do seznamu podvodníků omylem, napište na [doplníme: e-mail]. " +
                "Do 3 pracovních dnů ho vyřadíme a už se do seznamu nevrátí. " +
                "Proti zpracování můžete podat námitku (čl. 21 GDPR).",

            "8. Záloha" to
                "Když máte v telefonu zapnutou zálohu Google, uloží se do vašeho účtu Google nastavení: kdo je v rodině " +
                "a jak vám říkají. Shrnutí dopisů a fotky se nezálohují.",

            "9. Předání mimo Evropskou unii" to
                "ntfy.sh, GitHub, Cloudflare i Anthropic sídlí v USA. U GitHubu a Cloudflare spoléháme na rozhodnutí Evropské komise " +
                "o odpovídající ochraně (EU–US Data Privacy Framework, čl. 45 GDPR). U ntfy.sh a Anthropic [doplníme po ověření]. " +
                "Do společného seznamu se posílá jen číslo podvodníka, adresa webu a náhodné označení telefonu. " +
                "Text dopisu jen se souhlasem podle bodu 4.",

            "10. Co neděláme" to
                "•  Údaje neprodáváme a nepoužíváme je k reklamě.\n" +
                "•  Nemáme uživatelské účty a nesbíráme statistiky o používání.\n" +
                "•  Text SMS a zpráv neposíláme žádné umělé inteligenci. Text dopisů jen tehdy, když s tím souhlasíte (bod 4).",

            "11. Vaše práva" to
                "Máte právo vědět, jaké údaje o vás máme, nechat je opravit nebo smazat, omezit jejich zpracování, " +
                "vznést námitku a odvolat souhlas (čl. 15 až 21 GDPR). Stížnost můžete podat Úřadu pro ochranu osobních " +
                "údajů, Pplk. Sochora 27, 170 00 Praha 7, www.uoou.gov.cz (čl. 77 GDPR).",

            "12. Bezpečnost a odpovědnost" to
                "Údaje v telefonu chrání Android. Spojení s ntfy.sh, GitHubem, Cloudflare i Anthropic je šifrované (HTTPS).\n\n" +
                "Varování je pomůcka. Nezaručí, že Postaráno pozná každý podvod, a může se splést i opačně. " +
                "Při pochybnostech vždy zavolejte někomu z rodiny nebo přímo bance či policii.",

            "13. Změny" to
                "O důležitých změnách vás předem upozorníme a požádáme o nový souhlas. Platí od [doplníme: datum].",
        )
    }
}
