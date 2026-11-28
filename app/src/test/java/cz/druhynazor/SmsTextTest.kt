package cz.druhynazor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTextTest {
    private fun gsm(s: String) = s.all { it == '\n' || it.code in 32..126 }

    @Test fun removesDiacriticsAndEmoji() {
        assertEquals("Postarano: Zkouska vysla \"ANO\" - ok...", SmsText.clean("Postaráno: ✅ Zkouška vyšla „ANO“ – ok…"))
    }

    @Test fun oneSmsAlert() {
        val head = SmsText.clean("Postaráno: Babi: podezřelá SMS")
        val tail = SmsText.clean("Odpovězte ANO 3 = v pořádku, NE 3 = podvod.")
        val body = SmsText.fit(SmsText.clean("Od 777123456: " + "Vaše zásilka čeká na doplatek 35 Kč, zaplaťte zde https://x.y/abc ".repeat(5)),
            SmsText.ONE_SMS - head.length - tail.length - 2)
        val text = listOf(head, body, tail).joinToString("\n")
        assertTrue(text.length <= SmsText.ONE_SMS)
        assertTrue(gsm(text))
        assertTrue(text.endsWith("NE 3 = podvod."))
    }

    @Test fun fitCutsAtWord() {
        assertEquals("jedna dva...", SmsText.fit("jedna dva tri ctyri", 13))
        assertEquals("kratke", SmsText.fit("kratke", 160))
    }

    @Test fun limits() {
        assertEquals(160, SmsText.limit(1))
        assertEquals(306, SmsText.limit(2))
    }
}
