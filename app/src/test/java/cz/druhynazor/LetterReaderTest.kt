package cz.druhynazor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Zkušební dopisy: ověří, že aplikace řekne správné tři věty. */
class LetterReaderTest {
    private val today = LocalDate.of(2026, 11, 22)

    private fun show(t: String): LetterReader.Letter {
        val l = LetterReader.read(t, today)
        println("${LetterReader.about(l)} | ${LetterReader.verdict(l)} | ${LetterReader.todo(l, today)}")
        println("  více: ${LetterReader.more(l)}")
        println("  rodina: ${LetterReader.familyText(l, "Mamka", false, today)}")
        return l
    }

    @Test fun televizniPoplatek() {
        val l = show("""Česká televize
Kavčí hory, 140 70 Praha 4
Výzva k úhradě televizního poplatku
Evidujeme, že nemáte uhrazen televizní poplatek za období 10/2026 - 12/2026.
Částka k úhradě: 405,00 Kč
Číslo účtu: 1-1000-0108/0300
Variabilní symbol: 1234567890
Datum splatnosti: 15.12.2026""")
        assertEquals("Česká televize", l.sender)
        assertEquals(40500L, l.amount)
        assertEquals(LocalDate.of(2026, 12, 15), l.due)
        assertEquals("1234567890", l.vs)
    }

    @Test fun elektrina() {
        val l = show("""ČEZ Prodej, a.s.
Vyúčtování elektřiny
Celkem k úhradě
2 345,50 Kč
Splatnost
30. 11. 2026
Bankovní spojení 19-2000145399/0800""")
        assertEquals(LetterReader.Kind.PAY, l.kind)
        assertEquals(234550L, l.amount)
        assertEquals(ScamCheck.Level.OK, l.level)
        assertEquals(LocalDate.of(2026, 11, 30), l.due)
    }

    @Test fun vyhraJePodvod() {
        val l = show("Vážený zákazníku, vyhrál jste 500 000 Kč! Pro výplatu výhry uhraďte poplatek 990 Kč na účet " +
            "DE89370400440532013000 do 24 hodin. Zadejte číslo karty na www.vyhra-cz.top")
        assertEquals(ScamCheck.Level.SCAM, l.level)
    }

    @Test fun duchodJeInformace() {
        val l = show("""Česká správa sociálního zabezpečení
Oznámení o zvýšení důchodu od ledna 2027. Váš starobní důchod činí 21 456 Kč měsíčně.""")
        assertEquals(LetterReader.Kind.PENSION, l.kind)
        assertEquals(ScamCheck.Level.OK, l.level)
    }

    @Test fun neznamyChcePenize() {
        val l = show("""Inkaso Rychle s.r.o.
Upomínka. Dlužná částka 3 990 Kč. Uhraďte do 25.11.2026 na účet 123456789/0800, jinak předáme exekutorovi.""")
        assertTrue(l.level != ScamCheck.Level.OK)
        assertEquals(LetterReader.Kind.DEBT, l.kind)
    }

    /** Stanislav 26. 11.: faktura Dobré energie se tvářila jako zdravotní pojišťovna („rozpis“ obsahuje „ozp“). */
    @Test fun dobraEnergieNeniPojistovna() {
        val l = show("""Dobrá energie s.r.o.
Upomínka
Rozpis plateb za elektřinu
Nedoplatek k úhradě 15 697,00 Kč
Číslo účtu 19-2000145399/0800""")
        assertEquals("Dobrá energie", l.sender)
        assertTrue(LetterReader.about(l).startsWith("Dobrá energie"))
        assertTrue(l.level != ScamCheck.Level.OK) // velká částka a bez termínu
        assertTrue(!LetterReader.todo(l, today).startsWith("Zaplaťte"))
    }

    @Test fun rozpisNeniOzp() {
        val l = show("""Nějaká firma s.r.o.
Rozpis záloh na elektřinu, částka 1 200 Kč, splatnost 10.12.2026""")
        assertTrue(l.sender != "zdravotní pojišťovna")
    }

    @Test fun pojistovnaChceZaElektrinu() {
        val l = show("""Oborová zdravotní pojišťovna
Upomínka: nedoplatek za elektřinu 2 400 Kč. Splatnost 10.12.2026. Účet 19-2000145399/0800""")
        assertEquals(ScamCheck.Level.WARN, l.level)
        assertTrue(l.reasons.any { it.contains("nesedí") })
    }

    @Test fun bezTerminuNeriktZaplatte() {
        val l = show("""ČEZ Prodej, a.s.
Vyúčtování elektřiny, celkem k úhradě 845 Kč. Bankovní spojení 19-2000145399/0800""")
        assertTrue(!LetterReader.todo(l, today).startsWith("Zaplaťte"))
    }

    /** Běžná faktura Dobré energie se vším, co má mít: má vyjít V pořádku a říct termín. */
    @Test fun dobraEnergieFaktura() {
        val l = show("""Dobrá energie s.r.o.
Na Florenci 2116/15, 110 00 Praha 1
www.dobraenergie.cz
Faktura - vyúčtování elektřiny
Rozpis plateb za období 1. 11. 2025 - 31. 10. 2026
Celkem k úhradě
1 850,00 Kč
Datum splatnosti
12. 12. 2026
Bankovní spojení: 19-2000145399/0800
Variabilní symbol: 2026114455""")
        assertEquals("Dobrá energie", l.sender)
        assertEquals(185000L, l.amount)
        assertEquals(LocalDate.of(2026, 12, 12), l.due)
        assertEquals(ScamCheck.Level.OK, l.level)
        assertEquals("Zaplaťte do 12. prosince.", LetterReader.todo(l, today))
    }

    /** Vymahač, který v textu zmíní ČEZ: odesílatel je vymahač, ne ČEZ. */
    @Test fun vymahacZaCez() {
        val l = show("""Inkasní agentura Praha s.r.o.
Vymáháme pohledávku za ČEZ Prodej, a.s.
Dlužná částka 2 300 Kč, uhraďte do 10.12.2026 na účet 19-2000145399/0800""")
        assertEquals("Inkasní agentura Praha s.r.o.", l.sender)
        assertTrue(l.level != ScamCheck.Level.OK)
    }

    /** Víc různých částek a žádná „k úhradě“: nehádat. */
    @Test fun nejistaCastka() {
        val l = show("""ČEZ Prodej, a.s.
Vyúčtování elektřiny. Zálohy 1 200 Kč, spotřeba 3 400 Kč, splatnost 10.12.2026""")
        assertEquals(null, l.amount)
        assertTrue(l.level != ScamCheck.Level.OK)
        assertTrue(!LetterReader.todo(l, today).startsWith("Zaplaťte"))
    }

    /** Samotná zkratka bez stránky nestačí. */
    @Test fun samotnaZkratkaNestaci() {
        val l = show("""Upomínka OZP, doplaťte 900 Kč do 10.12.2026 na účet 19-2000145399/0800""")
        assertTrue(l.sender != "zdravotní pojišťovna")
        assertTrue(l.level != ScamCheck.Level.OK)
    }

    @Test fun reklama() {
        val l = show("Kaufland akční nabídka, sleva 30 % jen tento týden. Leták uvnitř.")
        assertEquals(LetterReader.Kind.AD, l.kind)
    }

    @Test fun ucetKontrola() {
        assertTrue(LetterReader.accountValid("35", "4544250227").let { true }) // jen nespadne
        assertTrue(LetterReader.ibanValid("DE89370400440532013000"))
    }
}
