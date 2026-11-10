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

/** Jedna zpráva: barevný verdikt nahoře, text bez klikacích odkazů, tlačítko na rodinu, odpověď. */
class MessageActivity : Activity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val stored = if (id > 0) SmsStore.byId(this, id) else null
        val address = stored?.address ?: intent.getStringExtra(EXTRA_ADDRESS) ?: ""
        val body = stored?.body ?: intent.getStringExtra(EXTRA_BODY) ?: ""
        if (id > 0) SmsStore.markRead(this, id)
        build(address, body, stored?.incoming ?: body.isNotEmpty())
    }

    private fun build(address: String, body: String, incoming: Boolean) {
        val prefs = Prefs(this)
        val root = Ui.column(this, 18, 16).apply { setBackgroundColor(Ui.BG) }

        if (body.isNotEmpty()) {
            val result = if (incoming) ScamCheck.check(body) else ScamCheck.Result(ScamCheck.Level.OK, "", emptyList())
            if (incoming) {
                val c = Ui.color(result.level)
                val banner = Ui.column(this, 20, 8).apply { background = Ui.rounded(c, 22, this@MessageActivity) }
                banner.addView(Ui.text(this, ScamCheck.headline(result.level), 34f, bold = true, color = Color.WHITE))
                banner.addView(Ui.text(this, ScamCheck.advice(result.level), 22f, bold = true, color = Color.WHITE))
                if (result.level != ScamCheck.Level.OK) {
                    banner.addView(Ui.text(this, result.reason, 18f, color = Color.WHITE))
                    banner.addView(Ui.text(this, "${prefs.familyName} už o tom ví.", 18f, color = Color.WHITE))
                }
                root.addView(banner)
                root.addView(Ui.button(this, "🔊 Přečíst nahlas", Ui.CARD, Ui.ACCENT, sp = 18f) {
                    say("${ScamCheck.headline(result.level)}. ${ScamCheck.advice(result.level)} ${if (result.level != ScamCheck.Level.OK) result.reason else ""}")
                })
            }

            val card = Ui.card(this)
            card.addView(Ui.text(this, if (incoming) "Od: $address" else "Komu: $address", 16f, color = Ui.MUTED))
            // Žádné klikací odkazy: text se zobrazí jen jako obyčejné písmo
            card.addView(Ui.text(this, body, 21f).apply { linksClickable = false; autoLinkMask = 0 })
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
        tts = TextToSpeech(this) { status ->
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
    }
}
