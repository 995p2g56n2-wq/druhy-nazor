package cz.druhynazor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Zprávy z Messengeru a Facebooku: podvody poznat, běžné povídání nechat být. */
class ScamCheckTest {

    private fun level(text: String): ScamCheck.Level {
        val r = ScamCheck.check(text, null)
        println("${r.level} ${r.signs} | $text")
        return r.level
    }

    private fun alarm(text: String) = assertNotEquals(text, ScamCheck.Level.OK, level(text))
    private fun ok(text: String) = assertEquals(text, ScamCheck.Level.OK, level(text))

    @Test fun ukradenyUcetChcePujcit() {
        alarm("Ahoj, nemáš mi půjčit 5000? Zítra ti to vrátím")
        alarm("Ahoj babi, potřebovala bych půjčit pár tisíc, pošlu ti číslo účtu")
        alarm("Mohla bys mi půjčit 3 000 Kč? Hned to vrátím")
        alarm("ahoj nemas pujcit 2000 do patku")
    }

    @Test fun chceKodZSms() {
        alarm("Ahoj, omylem jsem ti poslala kód na tvoje číslo, můžeš mi ho přeposlat?")
        alarm("Přišel ti teď kód? Pošli mi ho prosím, potřebuju to")
        alarm("Pošli mi ten kód co ti přišel")
        alarm("Přijde ti 6místný kód, napiš mi ho sem")
    }

    @Test fun hlasovaniSOdkazem() {
        alarm("Ahoj, hlasuj prosím pro moji neteř, vyhraje zájezd https://hlasovani-soutez.top/n12")
    }

    @Test fun jsiToTyNaVideu() {
        alarm("Jsi to ty na tom videu? 😱 https://bit.ly/3xYz")
        alarm("Jsi to ty v tom videu?? fb-video.click/a8")
    }

    @Test fun bazarPrijetiPlatby() {
        alarm("Dobrý den, zboží beru, pošlu přes Zásilkovnu. Pro přijetí platby klikněte zde a vyplňte údaje karty")
    }

    @Test fun zablokovaniUctu() {
        alarm("Meta Support: Váš účet bude trvale zablokován kvůli porušení zásad. Ověřte se do 24 hodin: meta-help.site/x")
    }

    @Test fun fotkaDokladu() {
        alarm("Pošli mi fotku občanky z obou stran, potřebuju to vyřídit")
        alarm("Vyfoť mi kartu z obou stran")
    }

    @Test fun beznePovidani() {
        ok("Ahoj mami, v neděli přijedeme na oběd, upeču bábovku")
        ok("To je hezká fotka! Jsi to ty v mládí?")
        ok("Půjčíš mi zítra tu vrtačku?")
        ok("Hlasování ve spolku je ve čtvrtek od šesti")
        ok("Posílám fotky z dovolené ❤️")
        ok("Rychle se uzdrav, babi")
        ok("To tvoje foto je krásné")
        ok("Dorazili jsme v pořádku, pozdravuj dědu")
    }
}
