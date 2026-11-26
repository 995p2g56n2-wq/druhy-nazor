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
                "•  fotky a text dopisů (text čte nástroj Google ML Kit přímo v telefonu, fotka dopisu se smaže po 60 dnech),\n" +
                "•  jména a čísla lidí z rodiny, které jste vybrali, a jejich rozhodnutí o zprávách,\n" +
                "•  připomínky plateb.\n\n" +
                "Tyto údaje nevidíme my ani nikdo jiný. Smažete je odinstalováním.",

            "3. Upozornění rodině (SMS a MMS)" to
                "Co: text podezřelé zprávy, číslo odesílatele, shrnutí nebo fotka dopisu.\n" +
                "Komu: jen lidem z rodiny, které jste vybrali, přes vašeho mobilního operátora.\n" +
                "Proč: aby vám rodina mohla poradit.\n" +
                "Jak dlouho: v telefonech rodiny, dokud zprávu nesmažou.\n" +
                "Právní důvod: váš souhlas (čl. 6 odst. 1 písm. a) GDPR). Souhlas můžete kdykoli odvolat odinstalováním " +
                "nebo odebráním lidí z rodiny v části Pro rodinu.",

            "4. Společný seznam podvodníků" to
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

            "5. Číslo v seznamu omylem" to
                "Pokud se vaše číslo dostalo do seznamu podvodníků omylem, napište na [doplníme: e-mail]. " +
                "Do 3 pracovních dnů ho vyřadíme a už se do seznamu nevrátí. " +
                "Proti zpracování můžete podat námitku (čl. 21 GDPR).",

            "6. Záloha" to
                "Když máte v telefonu zapnutou zálohu Google, uloží se do vašeho účtu Google nastavení: kdo je v rodině " +
                "a jak vám říkají. Shrnutí dopisů a fotky se nezálohují.",

            "7. Předání mimo Evropskou unii" to
                "ntfy.sh i GitHub sídlí v USA. U GitHubu spoléháme na rozhodnutí Evropské komise o odpovídající ochraně " +
                "(EU–US Data Privacy Framework, čl. 45 GDPR). U ntfy.sh [doplníme po ověření]. " +
                "Posílá se tam jen číslo podvodníka, adresa webu a náhodné označení telefonu.",

            "8. Co neděláme" to
                "•  Údaje neprodáváme a nepoužíváme je k reklamě.\n" +
                "•  Nemáme uživatelské účty a nesbíráme statistiky o používání.\n" +
                "•  Text zpráv ani dopisů neposíláme žádné umělé inteligenci. Kdyby se to změnilo, nejdřív vás požádáme o souhlas.",

            "9. Vaše práva" to
                "Máte právo vědět, jaké údaje o vás máme, nechat je opravit nebo smazat, omezit jejich zpracování, " +
                "vznést námitku a odvolat souhlas (čl. 15 až 21 GDPR). Stížnost můžete podat Úřadu pro ochranu osobních " +
                "údajů, Pplk. Sochora 27, 170 00 Praha 7, www.uoou.gov.cz (čl. 77 GDPR).",

            "10. Bezpečnost a odpovědnost" to
                "Údaje v telefonu chrání Android. Spojení s ntfy.sh a GitHubem je šifrované (HTTPS).\n\n" +
                "Varování je pomůcka. Nezaručí, že Postaráno pozná každý podvod, a může se splést i opačně. " +
                "Při pochybnostech vždy zavolejte někomu z rodiny nebo přímo bance či policii.",

            "11. Změny" to
                "O důležitých změnách vás předem upozorníme a požádáme o nový souhlas. Platí od [doplníme: datum].",
        )
    }
}
