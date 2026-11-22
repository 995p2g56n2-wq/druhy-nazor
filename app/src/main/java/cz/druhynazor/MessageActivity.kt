package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

private data class Banner(val color: Int, val head: String, val advice: String, val extra: String)

/** Jedna zpráva: barevný verdikt nahoře, text bez klikacích odkazů, tlačítko na rodinu, odpověď. */
class MessageActivity : Activity() {

    private var tts: TextToSpeech? = null
    private var address = ""
    private var body = ""
    private var incoming = false
    private var key = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val stored = if (id > 0) SmsStore.byId(this, id) else null
        address = stored?.address ?: intent.getStringExtra(EXTRA_ADDRESS) ?: ""
        body = stored?.body ?: intent.getStringExtra(EXTRA_BODY) ?: ""
        incoming = stored?.incoming ?: body.isNotEmpty()
        key = intent.getStringExtra(EXTRA_KEY)
            ?: if (id > 0) Verdicts.keyForSms(id) else Verdicts.keyForText(address, body)
        if (id > 0) SmsStore.markRead(this, id)
        build()
    }

    override fun onResume() {
        super.onResume()
        // Mezitím mohla rodina zprávu zkontrolovat
        if (incoming && body.isNotEmpty()) Verdicts.pollNow(this) { changed -> if (changed && !isFinishing) build() }
    }

    private fun build() {
        val prefs = Prefs(this)
        val root = Ui.column(this, 18, 16).apply { setBackgroundColor(Ui.BG) }
        val fromFamily = incoming && prefs.familyByNumber(address) != null
        val verdict = if (incoming && !fromFamily) Verdicts.get(this, key) else null

        if (body.isNotEmpty()) {
            val result = if (incoming && !fromFamily) SharedList.check(this, body, address) else ScamCheck.Result(ScamCheck.Level.OK, "", emptyList())
            if (incoming) {
                val family = Verdicts.decidedBy(this, key) ?: prefs.familyName
                val (c, head, advice, extra) = when {
                    verdict == Verdicts.Verdict.OK -> Banner(Ui.OK, "V POŘÁDKU",
                        "$family říká: je to v pořádku.", "Odkaz můžete otevřít.")
                    verdict == Verdicts.Verdict.SCAM -> Banner(Ui.BAD, "PODVOD",
                        "$family říká: je to podvod.", "Na nic neklikejte, nic neplaťte a neodpovídejte.")
                    result.level == ScamCheck.Level.WARN -> Banner(Ui.color(result.level), ScamCheck.headline(result.level),
                        ScamCheck.advice(result.level), "${result.reason}\nČekáme, až to $family zkontroluje.")
                    result.level == ScamCheck.Level.SCAM -> Banner(Ui.color(result.level), ScamCheck.headline(result.level),
                        ScamCheck.advice(result.level), "${result.reason}\n$family už o tom ví.")
                    else -> Banner(Ui.color(result.level), ScamCheck.headline(result.level), ScamCheck.advice(result.level),
                        if (result.promo) result.reason else "")
                }
                val banner = Ui.column(this, 20, 8).apply { background = Ui.rounded(c, 22, this@MessageActivity) }
                banner.addView(Ui.text(this, head, 34f, bold = true, color = Color.WHITE))
                banner.addView(Ui.text(this, advice, 22f, bold = true, color = Color.WHITE))
                if (extra.isNotEmpty()) banner.addView(Ui.text(this, extra, 18f, color = Color.WHITE))
                root.addView(banner)
                root.addView(Ui.button(this, "🔊 Přečíst nahlas", Ui.CARD, Ui.ACCENT, sp = 18f) {
                    say("$head. $advice ${extra.replace("\n", " ")}")
                })
            }

            val card = Ui.card(this)
            card.addView(Ui.text(this, if (incoming) "Od: $address" else "Komu: $address", 16f, color = Ui.MUTED))
            val trusted = !incoming || verdict == Verdicts.Verdict.OK ||
                (verdict == null && result.level == ScamCheck.Level.OK)
            card.addView(Ui.text(this, body, 21f).apply {
                if (trusted) {
                    // Zkontrolovaná nebo bezpečná zpráva: odkazy jdou otevřít
                    autoLinkMask = android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.PHONE_NUMBERS
                    text = body
                } else {
                    // Podezřelá zpráva: žádné klikací odkazy, jen obyčejné písmo
                    linksClickable = false; autoLinkMask = 0
                }
            })
            root.addView(card)
        } else {
            root.addView(Ui.text(this, "Nová zpráva pro: $address", 22f, bold = true))
        }

        root.addView(Ui.button(this, "📞 Zavolat: ${prefs.familyName}", Ui.ACCENT) {
            if (prefs.familyPhone.isBlank()) {
                Toast.makeText(this, "Nejdřív vyplňte telefon rodiny v aplikaci.", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + prefs.familyPhone)))
            }
        })

        if (address.isNotBlank() && !intent.getBooleanExtra(EXTRA_CHAT, false)) {
            val reply = Ui.input(this, "", "Napsat odpověď…", multi = true)
            root.addView(reply)
            root.addView(Ui.button(this, "Odeslat", Ui.CARD, Ui.ACCENT, sp = 18f) {
                val t = reply.text.toString().trim()
                if (t.isNotEmpty()) {
                    try {
                        SmsStore.send(this, address, t)
                        reply.setText("")
                        Toast.makeText(this, "Odesláno", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Nepodařilo se odeslat: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun say(text: String) {
        tts?.shutdown()
        tts = Voice.tts(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("cs", "CZ")
                tts?.setSpeechRate(0.9f)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dn")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_BODY = "body"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_KEY = "verdict_key"
    }
}
