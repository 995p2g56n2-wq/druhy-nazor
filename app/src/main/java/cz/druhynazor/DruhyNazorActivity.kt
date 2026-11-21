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

/**
 * Dvě obrazovky v jedné:
 * - Zprávy (pro seniora): jen seznam zpráv.
 * - Pro rodinu: dokončit nastavení, komu psát, jak seniorovi říkají a schované nastavení pro zkoušení.
 */
class DruhyNazorActivity : Activity() {

    private val onlyMessages get() = intent?.getBooleanExtra(EXTRA_ONLY_MESSAGES, false) == true
    private var testShown = false

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

        if (onlyMessages) {
            root.addView(Ui.text(this, "Zprávy", 32f, bold = true))
            if (!permOk) {
                root.addView(Ui.text(this, "Zprávy se ukážou, až rodina dokončí nastavení.", 19f, color = Ui.MUTED))
            } else {
                val list = SmsStore.latest(this)
                if (list.isEmpty()) root.addView(Ui.text(this, "Zatím žádné zprávy.", 19f, color = Ui.MUTED))
                for (sms in list) root.addView(messageRow(sms))
            }
            show(root); return
        }

        // ---- Pro rodinu ----
        root.addView(Ui.text(this, "Pro rodinu", 32f, bold = true))
        if (DnSetupActivity.allDone(this)) {
            root.addView(Ui.text(this, "✅ Ochrana je celá zapnutá.", 20f, bold = true, color = Ui.OK))
        } else {
            val setup = Ui.card(this).apply { background = Ui.rounded(Ui.WARN_SOFT, 20, this@DruhyNazorActivity) }
            setup.addView(Ui.text(this, "Ochrana není celá zapnutá", 22f, bold = true, color = Ui.WARN))
            setup.addView(Ui.text(this, "Chybí:", 17f, bold = true))
            setup.addView(Ui.text(this, DnSetupActivity.missing(this).joinToString("\n") { "•  $it" }, 18f))
            if (DnSetupActivity.chatsMissing(this)) {
                setup.addView(Ui.text(this,
                    "Když je u WhatsAppu přepínač šedý: otevřete Nastavení telefonu → Aplikace → Postaráno → " +
                    "tři tečky vpravo nahoře → Povolit omezená nastavení. Pak znovu Dokončit nastavení.", 16f, color = Ui.MUTED))
            }
            setup.addView(Ui.button(this, "Dokončit nastavení", Ui.WARN) {
                startActivity(Intent(this, DnSetupActivity::class.java))
            })
            root.addView(setup)
        }

        // ---- Ikony na plochu (jedním ťuknutím, kdyby je někdo omylem odebral) ----
        val icons = Ui.card(this)
        icons.addView(Ui.text(this, "Ikony na plochu", 22f, bold = true))
        icons.addView(Ui.text(this, "Když ikona z plochy zmizí, ťukněte sem a pak v okénku na Přidat.", 17f, color = Ui.MUTED))
        icons.addView(Ui.button(this, "✉️  Dát na plochu obálku Zprávy", Ui.CARD, Ui.ACCENT, sp = 18f) {
            DnSetupActivity.pinShortcut(this, "zpravy")
        })
        icons.addView(Ui.button(this, "🛡️  Dát na plochu štít Postaráno", Ui.CARD, Ui.ACCENT, sp = 18f) {
            DnSetupActivity.pinShortcut(this, "app")
        })
        root.addView(icons)

        // ---- Schovat původní Zprávy (nepovinné, dělá rodina) ----
        if (!prefs.uklidDone) {
            val hide = Ui.card(this)
            hide.addView(Ui.text(this, "Schovat původní Zprávy", 22f, bold = true))
            hide.addView(Ui.text(this, "Nepovinné. Na ploše pak zůstane jen naše obálka ✉️ Zprávy, která hlídá podvody. Postup:", 17f, color = Ui.MUTED))
            hide.addView(Ui.text(this,
                "1.  Ťukněte dole na tlačítko Domů.\n" +
                "2.  Podržte prst na původní ikoně Zprávy. To je ta s bublinou, ne naše obálka ✉️.\n" +
                "3.  Stiskněte Odebrat. Pozor, ne Odinstalovat.\n" +
                "4.  Vraťte se sem: ťukněte na ikonu Postaráno se štítem a dole na Pro rodinu.", 18f))
            hide.addView(Ui.button(this, "Hotovo, je schovaná", Ui.CARD, Ui.ACCENT, sp = 18f) {
                prefs.uklidDone = true; render()
            })
            root.addView(hide)
        }

        root.addView(FamilyUi.card(this) { render() })

        // ---- Jak seniorovi říkají ----
        val call = Ui.card(this)
        call.addView(Ui.text(this, "Jak mu v rodině říkáte", 22f, bold = true))
        call.addView(Ui.text(this, "Takhle bude psát v SMS, třeba „${prefs.seniorName}: podvodná SMS“.", 17f, color = Ui.MUTED))
        (Prefs.CALLS.map { it.second } + Prefs.OTHER).chunked(3).forEach { names ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            names.forEachIndexed { i, name ->
                val on = prefs.seniorName == name
                val b = Ui.button(this, name, if (on) Ui.ACCENT else Ui.CARD,
                    if (on) android.graphics.Color.WHITE else Ui.ACCENT, sp = 16f) {
                    prefs.seniorName = name; render()
                }.apply { setPadding(dp(4), dp(14), dp(4), dp(14)) }
                row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i < 2) marginEnd = dp(8)
                })
            }
            // Zarovnat poslední řádek na stejnou šířku tlačítek
            repeat(3 - names.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            call.addView(row)
        }
        root.addView(call)

        // ---- Zkušební nastavení (jen pro zkoušení, schované) ----
        if (!testShown) {
            root.addView(Ui.button(this, "Nastavení pro zkoušení", Ui.BG, Ui.MUTED, sp = 16f) { testShown = true; render() })
            show(root); return
        }
        val cfg = Ui.card(this)
        cfg.addView(Ui.text(this, "Nastavení pro zkoušení", 22f, bold = true))

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
            prefs.testScamNumbers = scam.text.toString().trim()
            prefs.longCallMinutes = minutes.text.toString().trim().toIntOrNull() ?: 3
            prefs.blockScamCalls = block.isChecked
            Toast.makeText(this, "Uloženo", Toast.LENGTH_SHORT).show()
        })

        cfg.addView(Ui.text(this, "Zkušební upozornění přes ntfy", 18f, bold = true))
        val ntfy = android.widget.CheckBox(this).apply {
            text = "Posílat upozornění i do aplikace ntfy (s tlačítky)"
            textSize = 17f
            setTextColor(Ui.INK)
            isChecked = prefs.ntfyEnabled
            setOnCheckedChangeListener { _, on -> prefs.ntfyEnabled = on }
        }
        cfg.addView(ntfy)
        cfg.addView(Ui.text(this,
            "V aplikaci „ntfy“ se přihlaste k odběru tohoto kanálu:", 16f, color = Ui.MUTED))
        cfg.addView(Ui.text(this, prefs.familyChannel, 20f, bold = true, color = Ui.ACCENT).apply { setTextIsSelectable(true) })
        cfg.addView(Ui.button(this, "Zkopírovat kanál", Ui.CARD, Ui.ACCENT, sp = 17f) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("kanal", prefs.familyChannel))
            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
        })
        cfg.addView(Ui.button(this, "Poslat zkušební upozornění do ntfy", Ui.CARD, Ui.ACCENT, sp = 17f) {
            Family.ntfy(this, "Postaráno: zkouška", "Upozornění funguje. Takhle vám přijde zpráva, když bude seniorovi hrozit podvod.", urgent = false)
            Toast.makeText(this, "Odesláno, podívejte se do ntfy", Toast.LENGTH_LONG).show()
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
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        cfg.addView(Ui.text(this, "Verze aplikace: $version", 15f, color = Ui.MUTED))
        root.addView(cfg)
        show(root)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (FamilyUi.onResult(this, requestCode, resultCode, data)) render()
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
        val fromFamily = Prefs(this).familyByNumber(sms.address) != null
        val checked = if (!sms.incoming) null
            else if (fromFamily) ScamCheck.Result(ScamCheck.Level.OK, "", emptyList()) else TrustedSenders.check(this, sms.body, sms.address)
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
            ScamCheck.Level.OK -> if (result.promo) "Reklama · " else ""
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
