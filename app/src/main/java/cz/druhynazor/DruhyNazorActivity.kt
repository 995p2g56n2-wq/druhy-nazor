package cz.druhynazor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import cz.druhynazor.Ui.dp
import java.util.Date

/** Druhý názor uvnitř Postaráno: stav ochrany, zprávy a nastavení pro test. */
class DruhyNazorActivity : Activity() {

    private val onlyMessages get() = intent?.getBooleanExtra(EXTRA_ONLY_MESSAGES, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (DnSetupActivity.permsGranted(this)) GuardService.start(this)
        render()
        Verdicts.pollNow(this) { changed -> if (changed && !isFinishing) render() }
    }

    private fun render() {
        val prefs = Prefs(this)
        val root = Ui.column(this, 16, 16).apply { setBackgroundColor(Ui.BG) }
        val permOk = DnSetupActivity.permsGranted(this)

        root.addView(Ui.button(this, "‹  Zpět na Postaráno", Ui.BG, Ui.ACCENT, sp = 18f) { backHome() })

        if (onlyMessages) {
            root.addView(Ui.text(this, "Zprávy", 32f, bold = true))
        } else {
            root.addView(Ui.text(this, "Druhý názor", 32f, bold = true))
            if (DnSetupActivity.allDone(this)) {
                root.addView(Ui.text(this, "✅ Ochrana je zapnutá. Hovory a SMS jsou pod dohledem.", 20f, bold = true, color = Ui.OK))
            } else {
                val setup = Ui.card(this)
                setup.addView(Ui.text(this, "Nastavení ještě není hotové", 22f, bold = true, color = Ui.WARN))
                setup.addView(Ui.text(this, "Bez něj vás aplikace nemusí včas varovat.", 17f, color = Ui.MUTED))
                setup.addView(Ui.button(this, "Dokončit nastavení") {
                    startActivity(Intent(this, DnSetupActivity::class.java))
                })
                root.addView(setup)
            }
        }

        // ---- Zprávy ----
        if (!onlyMessages) root.addView(Ui.text(this, "Zprávy", 24f, bold = true))
        if (!permOk) {
            root.addView(Ui.text(this, "Zprávy se ukážou po povolení oprávnění.", 17f, color = Ui.MUTED))
        } else {
            val list = SmsStore.latest(this)
            if (list.isEmpty()) root.addView(Ui.text(this, "Zatím žádné zprávy.", 17f, color = Ui.MUTED))
            for (sms in list) root.addView(messageRow(sms))
        }

        if (onlyMessages) { show(root); return }

        // ---- Zkušební nastavení ----
        val cfg = Ui.card(this)
        cfg.addView(Ui.text(this, "Nastavení pro test", 22f, bold = true))

        cfg.addView(Ui.text(this, "Jak oslovit seniora v upozornění", 16f, color = Ui.MUTED))
        val senior = Ui.input(this, prefs.seniorName, "Babička"); cfg.addView(senior)
        cfg.addView(Ui.text(this, "Kdo z rodiny dostává upozornění", 16f, color = Ui.MUTED))
        val famName = Ui.input(this, prefs.familyName, "Tomáš"); cfg.addView(famName)
        cfg.addView(Ui.text(this, "Jeho telefon (pro tlačítko Zavolat)", 16f, color = Ui.MUTED))
        val famPhone = Ui.input(this, prefs.familyPhone, "777 123 456", phone = true); cfg.addView(famPhone)
        cfg.addView(Ui.text(this, "Čísla, která se v testu chovají jako podvodníci (oddělte čárkou)", 16f, color = Ui.MUTED))
        val scam = Ui.input(this, prefs.testScamNumbers, "např. 777 111 222", multi = true); cfg.addView(scam)
        cfg.addView(Ui.text(this, "Po kolika minutách hovoru s neznámým číslem upozornit rodinu", 16f, color = Ui.MUTED))
        val minutes = Ui.input(this, prefs.longCallMinutes.toString(), "3", phone = true); cfg.addView(minutes)
        val block = android.widget.CheckBox(this).apply {
            text = "Podvodná čísla rovnou blokovat (telefon vůbec nezazvoní)"
            textSize = 17f
            setTextColor(Ui.INK)
            isChecked = prefs.blockScamCalls
        }
        cfg.addView(block)
        cfg.addView(Ui.button(this, "Uložit nastavení", Ui.ACCENT, sp = 19f) {
            prefs.seniorName = senior.text.toString().trim().ifEmpty { "Babička" }
            prefs.familyName = famName.text.toString().trim().ifEmpty { "Tomáš" }
            prefs.familyPhone = famPhone.text.toString().trim()
            prefs.testScamNumbers = scam.text.toString().trim()
            prefs.longCallMinutes = minutes.text.toString().trim().toIntOrNull() ?: 3
            prefs.blockScamCalls = block.isChecked
            Toast.makeText(this, "Uloženo", Toast.LENGTH_SHORT).show()
        })

        cfg.addView(Ui.text(this, "Kanál pro upozornění rodině", 18f, bold = true))
        cfg.addView(Ui.text(this,
            "Na iPhone člena rodiny nainstalujte zdarma aplikaci „ntfy“ a přihlaste se k odběru tohoto kanálu:", 16f, color = Ui.MUTED))
        cfg.addView(Ui.text(this, prefs.familyChannel, 20f, bold = true, color = Ui.ACCENT).apply { setTextIsSelectable(true) })
        cfg.addView(Ui.button(this, "Zkopírovat kanál", Ui.CARD, Ui.ACCENT, sp = 17f) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("kanal", prefs.familyChannel))
            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
        })
        cfg.addView(Ui.button(this, "Poslat rodině zkušební upozornění", Ui.CARD, Ui.ACCENT, sp = 17f) {
            Family.notify(this, "Druhý názor: zkouška", "Upozornění funguje. Takhle vám přijde zpráva, když bude seniorovi hrozit podvod.", urgent = false)
            Toast.makeText(this, "Odesláno, podívejte se do ntfy na iPhonu", Toast.LENGTH_LONG).show()
        })
        cfg.addView(Ui.button(this, "Ukázat varování před podvodníkem", Ui.CARD, Ui.ACCENT, sp = 17f) {
            startActivity(Intent(this, ScamCallActivity::class.java).putExtra(ScamCallActivity.EXTRA_NUMBER, "777 000 000 (zkouška)"))
        })
        cfg.addView(Ui.button(this, "Ukázat otázku po hovoru", Ui.CARD, Ui.ACCENT, sp = 17f) {
            startActivity(Intent(this, QuestionActivity::class.java).putExtra(QuestionActivity.EXTRA_NUMBER, "777 000 000 (zkouška)"))
        })
        cfg.addView(Ui.text(this,
            "Až budete test končit: Nastavení telefonu → Aplikace → Výchozí aplikace → SMS a vraťte původní aplikaci Zprávy.",
            15f, color = Ui.MUTED))
        cfg.addView(Ui.button(this, "Odebrat Druhý názor z úvodní obrazovky", Ui.CARD, Ui.BAD, sp = 17f) {
            Modules.uninstall(this, Modules.DN)
            Toast.makeText(this, "Odebráno. Vrátit ho jde přes „Přidat pomocníka“.", Toast.LENGTH_LONG).show()
            backHome()
        })
        root.addView(cfg)
        show(root)
    }

    /** Zpět na úvodní obrazovku, i když se Druhý názor otevřel z upozornění. */
    private fun backHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() { backHome() }

    private fun show(root: View) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG); isFillViewport = true; addView(root)
        })
    }

    private fun messageRow(sms: Sms): View {
        val checked = if (sms.incoming) ScamCheck.check(sms.body) else null
        // Rozhodnutí rodiny má přednost před automatickou kontrolou
        val result = when (if (sms.incoming) Verdicts.get(this, Verdicts.keyForSms(sms.id)) else null) {
            Verdicts.Verdict.OK -> ScamCheck.Result(ScamCheck.Level.OK, "", emptyList())
            Verdicts.Verdict.SCAM -> ScamCheck.Result(ScamCheck.Level.SCAM, "", emptyList())
            null -> checked
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.CARD, 16, this@DruhyNazorActivity)
            setPadding(0, dp(12), dp(14), dp(12))
            setOnClickListener {
                startActivity(Intent(this@DruhyNazorActivity, MessageActivity::class.java).putExtra(MessageActivity.EXTRA_ID, sms.id))
            }
        }
        val stripe = View(this).apply {
            background = Ui.rounded(if (result == null) Ui.MUTED else Ui.color(result.level), 4, this@DruhyNazorActivity)
        }
        row.addView(stripe, LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
        val col = Ui.column(this, 0, 4)
        val label = when (result?.level) {
            ScamCheck.Level.SCAM -> "⚠️ PODVOD · "
            ScamCheck.Level.WARN -> "⚠️ POZOR · "
            ScamCheck.Level.OK -> ""
            null -> "Odesláno · "
        }
        col.addView(Ui.text(this, label + sms.address, 17f, bold = true,
            color = if (result == null) Ui.MUTED else if (result.level == ScamCheck.Level.OK) Ui.INK else Ui.color(result.level)))
        col.addView(Ui.text(this, sms.body.take(120) + if (sms.body.length > 120) "…" else "", 16f).apply { maxLines = 3 })
        col.addView(Ui.text(this, DateFormat.format("d. M. HH:mm", Date(sms.date)), 13f, color = Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    companion object {
        const val EXTRA_ONLY_MESSAGES = "only_messages"
    }
}
