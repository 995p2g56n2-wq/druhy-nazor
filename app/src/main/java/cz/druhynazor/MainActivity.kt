package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp

/**
 * Postaráno – rodinný štít.
 *
 * Nastavení se na nic neptá, co může aplikace zjistit nebo rozhodnout sama:
 * 1. Zapnout ochranu  2. Komu dáme vědět  3. Jak vám říká  4. povolení  5. Hotovo.
 * Katalog pomocníků není, hotoví pomocníci jsou zapnutí sami.
 *
 * Úvodní obrazovka pro seniora: velký stav (zelená / oranžová), dlaždice Zprávy a malé tlačítko Pro rodinu.
 * Senior nic nenastavuje. Když něco nefunguje, rodina dostane SMS.
 */
class MainActivity : Activity() {

    private enum class Screen { START, FAMILY, CALL, DONE, HOME }

    private var screen = Screen.HOME
    private var manualShown = false
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        speaker = Speaker(this)
        screen = if (Modules.onboarded(this)) Screen.HOME else Screen.START
        // Mluvit už od výběru rodiny: zjistit hned, jestli telefon umí česky
        if (screen == Screen.START) Voice.check(this) { ok -> Prefs(this).voiceOk = ok }
        handleSendTo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSendTo(intent)
    }

    /** Jiná aplikace chce poslat SMS (např. tlačítko „Napsat zprávu“ v kontaktech). */
    private fun handleSendTo(i: Intent?) {
        if (i?.action == Intent.ACTION_SENDTO || i?.action == Intent.ACTION_SEND) {
            val number = i.data?.schemeSpecificPart ?: ""
            startActivity(Intent(this, MessageActivity::class.java).putExtra(MessageActivity.EXTRA_ADDRESS, number))
        }
    }

    override fun onResume() {
        super.onResume()
        BackGuide.uiVisible = true
        if (Modules.DN in Modules.installed(this) && DnSetupActivity.permsGranted(this)) GuardService.start(this)
        render()
        // Hlídání po spuštění chvíli nabíhá (třeba po aktualizaci), stav pak zkontrolovat znovu.
        // Teprve potom případně dát vědět rodině, že ochrana není celá zapnutá.
        window.decorView.postDelayed({
            if (screen == Screen.HOME && !isFinishing) { render(); alertFamilyIfNeeded() }
        }, 3000)
    }

    override fun onPause() {
        super.onPause()
        BackGuide.uiVisible = false
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun go(s: Screen) { screen = s; render() }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        when (screen) {
            Screen.FAMILY -> go(Screen.START)
            Screen.CALL -> go(Screen.FAMILY)
            Screen.DONE -> go(Screen.HOME)
            Screen.START, Screen.HOME -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun render() {
        val root = Ui.column(this, 20, 18).apply { setBackgroundColor(Ui.BG) }
        when (screen) {
            Screen.START -> start(root)
            Screen.FAMILY -> familyScreen(root)
            Screen.CALL -> callScreen(root)
            Screen.DONE -> done(root)
            Screen.HOME -> home(root)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun spacer(root: LinearLayout, dpH: Int = 24) =
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(dpH)))

    private fun canSms() = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // ---------- 1. Úvod: jedno tlačítko ----------

    private fun start(root: LinearLayout) {
        spacer(root, 40)
        root.addView(Ui.text(this, "🛡️", 80f, center = true))
        root.addView(Ui.text(this, "Postaráno", 36f, bold = true, center = true))
        root.addView(Ui.text(this, "Ochrana před podvody.\nRodina je vždy v obraze.", 24f, bold = true, center = true))
        root.addView(Ui.text(this, "Teď ťukněte dole na Zapnout ochranu.", 20f, center = true))
        spacer(root, 40)
        root.addView(Ui.text(this, "👇", 44f, center = true))
        root.addView(Ui.button(this, "Zapnout ochranu", sp = 24f) {
            // Rodina se obnovila ze zálohy (nový telefon): rovnou na povolení
            if (Prefs(this).familyContacts.isNotEmpty()) startSetup() else go(Screen.FAMILY)
        })
    }

    // ---------- 2. Komu dáme vědět ----------

    private fun familyScreen(root: LinearLayout) {
        spacer(root, 16)
        root.addView(Ui.text(this, "👨‍👩‍👧", 72f, center = true))
        root.addView(Ui.text(this, "Komu dáme vědět, když se něco stane?", 28f, bold = true, center = true))
        val say = "Komu dáme vědět, když se něco stane? Vyberte někoho z rodiny, přijde mu jen SMS. Teď stiskněte dole Vybrat z kontaktů."
        root.addView(Ui.text(this, "Vyberte někoho z rodiny, přijde mu jen SMS. Teď stiskněte dole Vybrat z kontaktů.", 20f, center = true))
        speakDone(say)
        spacer(root, 16)
        root.addView(Ui.button(this, "Vybrat z kontaktů", sp = 22f) { FamilyUi.pick(this) })
        if (manualShown) {
            FamilyUi.manual(this, root) { if (Prefs(this).familyContacts.isNotEmpty()) go(Screen.CALL) else render() }
        } else {
            root.addView(Ui.button(this, "Napsat číslo ručně", Ui.BG, Ui.MUTED, sp = 16f) { manualShown = true; render() })
        }
    }

    // ---------- 3. Jak vám říká ----------

    private fun callScreen(root: LinearLayout) {
        val prefs = Prefs(this)
        spacer(root, 16)
        root.addView(Ui.text(this, "Jak vám ${prefs.familyFirstName} říká?", 28f, bold = true, center = true))
        root.addView(Ui.text(this, "Podle toho mu budu psát. Teď vyberte jedno z tlačítek.", 20f, center = true, color = Ui.MUTED))
        speakDone("Jak vám ${prefs.familyFirstName} říká? Podle toho mu budu psát. Teď vyberte jedno z tlačítek.")
        for ((label, name) in Prefs.CALLS) {
            root.addView(Ui.button(this, label, Ui.CARD, Ui.INK, sp = 24f) { chooseCall(name) })
        }
        root.addView(Ui.button(this, "Jinak", Ui.BG, Ui.MUTED, sp = 18f) { chooseCall(Prefs.OTHER) })
    }

    private fun chooseCall(name: String) {
        Prefs(this).seniorName = name
        startSetup()
    }

    // ---------- 4. Povolení ----------

    private fun startSetup() {
        Modules.setOnboarded(this)
        // Po návratu z průvodce (i přes Zpět) se ukáže kontrola na konec.
        screen = Screen.DONE
        if (!DnSetupActivity.allDone(this)) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, DnSetupActivity::class.java), REQ_DN_SETUP)
        } else render()
    }

    // ---------- 5. Kontrola na konec ----------

    /**
     * Zelená = všechno zapnuté, rodina dostala první SMS s vysvětlením.
     * Oranžová = něco chybí, jedním ťuknutím se dokončí.
     */
    private fun done(root: LinearLayout) {
        val ok = DnSetupActivity.allDone(this)
        val prefs = Prefs(this)
        val family = prefs.familyContacts

        // První SMS rodině: vysvětlení a zkouška. Jen jednou.
        if (family.isNotEmpty() && !prefs.testSent && canSms()) {
            prefs.testSent = true
            Family.sms(this, FamilyUi.welcomeText(prefs) + " Toto je zkouška, teď nemusíte odpovídat.")
        }

        root.setBackgroundColor(if (ok) Ui.OK_SOFT else Ui.WARN_SOFT)
        spacer(root, 40)
        root.addView(Ui.text(this, if (ok) "✅" else "⚠️", 80f, center = true))
        val lines = if (ok) {
            listOfNotNull("Hotovo, jste chránění.",
                if (prefs.testSent && family.isNotEmpty()) "${prefs.familyFirstName} už má zkušební zprávu." else null,
                "Teď zmáčkněte dole Hotovo.")
        } else {
            listOf("Skoro hotovo.", "Ochrana ještě není celá zapnutá.", "Teď stiskněte dole Dokončit.")
        }
        root.addView(Ui.text(this, lines[0], 36f, bold = true, center = true, color = if (ok) Ui.OK else Ui.WARN))
        lines.drop(1).forEach { root.addView(Ui.text(this, it, 24f, center = true)) }
        speakDone(lines.joinToString(" "))
        spacer(root, 40)
        if (ok) {
            root.addView(Ui.button(this, "Hotovo", Ui.OK) { go(Screen.HOME) })
        } else {
            root.addView(Ui.button(this, "Dokončit", Ui.WARN) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, DnSetupActivity::class.java), REQ_DN_SETUP)
            })
            root.addView(Ui.button(this, "Později", Ui.BG, Ui.MUTED, sp = 16f) { go(Screen.HOME) })
        }
    }

    private var spokenDone: String? = null
    private fun speakDone(text: String) {
        if (spokenDone == text || !Prefs(this).voiceOk) return
        spokenDone = text
        speaker.say(text)
    }

    // ---------- Úvodní obrazovka seniora ----------

    private fun home(root: LinearLayout) {
        val prefs = Prefs(this)
        val ok = DnSetupActivity.allDone(this)
        val family = prefs.familyContacts

        // Velký stav. Senior tu nic nenastavuje, jen vidí, že je v bezpečí.
        val status = Ui.card(this, 8).apply {
            background = Ui.rounded(if (ok) Ui.OK_SOFT else Ui.WARN_SOFT, 22, this@MainActivity)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        status.addView(Ui.text(this, if (ok) "✅" else "⚠️", 64f, center = true))
        status.addView(Ui.text(this, if (ok) "Jste chránění" else "Ochrana není celá zapnutá", 30f, bold = true,
            center = true, color = if (ok) Ui.OK else Ui.WARN))
        val sub = when {
            ok -> "Hovory a zprávy hlídám za vás."
            family.isNotEmpty() && canSms() -> "${prefs.familyFirstName} už o tom ví a opraví to."
            else -> "Zavolejte rodině, ať vám to dokončí."
        }
        status.addView(Ui.text(this, sub, 21f, center = true))
        root.addView(status)

        // Dlaždice jen pro to, na co senior opravdu klepne
        val tiles = mutableListOf<Pair<String, () -> Unit>>()
        tiles += "✉️\nZprávy" to {
            startActivity(Intent(this, DruhyNazorActivity::class.java).putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true))
        }
        // Další hotoví pomocníci (Dopisy, Energie) sem přibydou sami.
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, open) ->
                val tile = Ui.button(this, label, Ui.CARD, Ui.INK, sp = 26f) { open() }.apply {
                    minHeight = dp(170)
                    maxLines = 4
                    ellipsize = null
                    setPadding(dp(6), dp(16), dp(6), dp(16))
                    background = Ui.rounded(Ui.CARD, 22, this@MainActivity, Ui.LINE)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0 && pair.size == 2) marginEnd = dp(14)
                })
            }
            root.addView(row)
        }

        spacer(root, 24)
        root.addView(Ui.button(this, "Pro rodinu", Ui.BG, Ui.MUTED, sp = 16f) {
            startActivity(Intent(this, DruhyNazorActivity::class.java))
        })
    }

    /**
     * Ochrana není celá zapnutá: rodina dostane SMS, co ťuknout. Nejvýš jednou za den,
     * senior to nemusí nikomu říkat.
     */
    private fun alertFamilyIfNeeded() {
        val prefs = Prefs(this)
        if (DnSetupActivity.allDone(this) || prefs.familyContacts.isEmpty() || !canSms()) return
        val now = System.currentTimeMillis()
        if (now - prefs.setupAlertTime < DAY) return
        prefs.setupAlertTime = now
        Family.sms(this, "Postaráno: Na telefonu ${prefs.seniorGenitive} není ochrana celá zapnutá. " +
            "Až u něj budete, otevřete Postaráno, ťukněte dole na Pro rodinu a pak na Dokončit nastavení.")
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (FamilyUi.onResult(this, requestCode, resultCode, data)) {
            if (screen == Screen.FAMILY && Prefs(this).familyContacts.isNotEmpty()) screen = Screen.CALL
        }
        render()
    }

    companion object {
        private const val REQ_DN_SETUP = 20
        private const val DAY = 24L * 3600 * 1000
    }
}
