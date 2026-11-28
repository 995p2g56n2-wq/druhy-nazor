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

    private enum class Screen { START, CONSENT, FAMILY, CALL, DONE, HOME }

    private var screen = Screen.HOME
    private var manualShown = false
    private var letterPrepared = false
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        speaker = Speaker(this)
        screen = if (Modules.onboarded(this)) Screen.HOME else Screen.START
        // Kdo ochranu zapnul ve starší verzi, odsouhlasí to jednou teď
        if (screen == Screen.HOME && !consented()) screen = Screen.CONSENT
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
        if (screen == Screen.HOME && !letterPrepared) { letterPrepared = true; LetterActivity.prepare(this) }
        // Léky a Jsem v pořádku: budíky naplánovat (nic se nezdvojí)
        Care.restore(this)
        // Od rodiny: pravidelná kontrola, a hned teď se podívat, jestli něco nepřišlo
        FamilyMail.schedule(this)
        Weather.schedule(this)
        if (screen == Screen.HOME) FamilyMail.checkNow(this) { n -> if (n > 0 && !isFinishing && screen == Screen.HOME) render() }
        // Hlídání po spuštění chvíli nabíhá (třeba po aktualizaci), stav pak zkontrolovat znovu.
        // Teprve potom případně dát vědět rodině, že ochrana není celá zapnutá.
        window.decorView.postDelayed({
            if (screen == Screen.HOME && !isFinishing) { render(); alertFamilyIfNeeded(); announceLetters(); Care.announce(this) }
        }, 3000)
    }

    override fun onPause() {
        super.onPause()
        BackGuide.uiVisible = false
        speaker.stop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    // Při odchodu z obrazovky hned umlknout, ať hlas nemluví o obrazovce, která už není vidět
    private fun go(s: Screen) { if (s != screen) speaker.stop(); screen = s; render() }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        when (screen) {
            Screen.CONSENT -> if (Modules.onboarded(this)) { @Suppress("DEPRECATION") super.onBackPressed() } else go(Screen.START)
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
            Screen.CONSENT -> consent(root)
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
            if (consented()) afterConsent() else go(Screen.CONSENT)
        })
    }

    private fun afterConsent() {
        when {
            // Ochrana už je celá zapnutá: rovnou domů
            Modules.onboarded(this) && DnSetupActivity.allDone(this) -> go(Screen.HOME)
            // Zapnutá jen napůl (třeba po přeinstalování se nastavení obnovilo ze zálohy, ale povolení chybí):
            // pokračovat v průvodci jako dřív, ne končit na domovské obrazovce
            Modules.onboarded(this) -> startSetup()
            // Rodina se obnovila ze zálohy (nový telefon): rovnou na povolení
            Prefs(this).familyContacts.isNotEmpty() -> startSetup()
            else -> go(Screen.FAMILY)
        }
    }

    // ---------- 1b. Souhlas ----------

    private fun consented() = Prefs(this).consentVersion >= CONSENT_VERSION

    private fun consent(root: LinearLayout) {
        spacer(root, 24)
        root.addView(Ui.text(this, "🤝", 64f, center = true))
        root.addView(Ui.text(this, "Než začneme", 30f, bold = true, center = true))
        val say = "Postaráno hlídá vaše zprávy, hovory a dopisy přímo v telefonu. " +
            "Když přijde podvod, pošle SMS vybraným lidem z rodiny a číslo podvodníka nahlásí, aby varovalo i ostatní. " +
            "Teď ťukněte dole na Souhlasím."
        root.addView(Ui.text(this, say, 21f, center = true))
        speakDone(say)
        spacer(root, 8)
        root.addView(Ui.button(this, "📄 Podrobné vysvětlení a Zásady", Ui.CARD, Ui.ACCENT, sp = 18f) {
            startActivity(Intent(this, PolicyActivity::class.java))
        })
        spacer(root, 8)
        root.addView(Ui.text(this, "👇", 44f, center = true))
        root.addView(Ui.button(this, "Souhlasím", sp = 24f) {
            Prefs(this).consentVersion = CONSENT_VERSION
            afterConsent()
        })
        spacer(root, 16)
        root.addView(Ui.text(this,
            "Varování je pomůcka a nepozná úplně každý podvod. Text zpráv nikam neposíláme, jen rodině v SMS. " +
                "Dopis přečte umělá inteligence, jen když s tím u dopisu souhlasíte. " +
                "Léky, odpověď na Jak se máte a místo, kde jste, když zmáčknete Pomoc, dostane jen rodina v SMS. " +
                "Fotky a vzkazy od rodiny leží 30 dní na našem serveru, pro počasí se posílá jen přibližné místo. " +
                "Hlášení do společného seznamu a každého pomocníka jde vypnout v části Pro rodinu.",
            16f, center = true, color = Ui.MUTED))
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
        root.addView(Ui.text(this, "Podle toho mu budu psát. Teď ťukněte dole na to, jak vám říká, třeba Mami nebo Babi.", 20f, center = true, color = Ui.MUTED))
        speakDone("Jak vám ${prefs.familyFirstName} říká? Podle toho mu budu psát. Teď ťukněte dole na to, jak vám říká, třeba Mami nebo Babi.")
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
            Family.sms(this, FamilyUi.welcomeText(prefs) + " Teď nemusíte odpovídat.", maxParts = 2)
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

        // Dlaždice jen pro to, na co senior opravdu klepne. Všechny stejně velké, Pomoc je červená.
        data class Tile(val label: String, val red: Boolean = false, val open: () -> Unit)
        val tiles = mutableListOf<Tile>()
        tiles += Tile("✉️\nZprávy") {
            startActivity(Intent(this, DruhyNazorActivity::class.java).putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true))
        }
        if (Modules.DOPIS in Modules.installed(this)) tiles += Tile("📄\nDopis") {
            startActivity(Intent(this, LetterActivity::class.java))
        }
        if (Care.enabled(this, Care.LEKY)) tiles += Tile("💊\nLéky") {
            startActivity(Intent(this, CareActivity::class.java).putExtra(CareActivity.EXTRA_MODE, CareActivity.MODE_OVERVIEW))
        }
        if (Care.enabled(this, Care.OK)) tiles += Tile("❤️\nJsem v pořádku") {
            startActivity(Intent(this, CareActivity::class.java).putExtra(CareActivity.EXTRA_MODE, CareActivity.MODE_CHECK)
                .putExtra(CareActivity.EXTRA_TILE, true))
        }
        if (FamilyMail.enabled(this) && family.isNotEmpty()) {
            val n = FamilyMail.unseen(this)
            tiles += Tile("📷\nOd rodiny" + if (n > 0) "\n🔴 $n nové" else "") {
                startActivity(Intent(this, FamilyMailActivity::class.java))
            }
        }
        if (Quiz.enabled(this)) tiles += Tile("🧩\nHádanka" + if (!Quiz.doneToday(this)) "\n🔴 dnešní" else "") {
            startActivity(Intent(this, QuizActivity::class.java))
        }
        if (Weather.enabled(this)) tiles += Tile("☀️\nPočasí") {
            startActivity(Intent(this, WeatherActivity::class.java))
        }
        if (Radio.enabled(this) && Radio.installed(this)) tiles += Tile("📻\nRádio") { Radio.open(this) }
        if (Care.enabled(this, BENEFITS)) tiles += Tile("💰\nNa co mám nárok") {
            startActivity(Intent(this, BenefitsActivity::class.java))
        }
        if (Care.enabled(this, Care.POMOC)) tiles += Tile("🆘\nPomoc", red = true) {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        // Další hotoví pomocníci sem přibydou sami.
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, t ->
                val bg = if (t.red) Ui.BAD else Ui.CARD
                val tile = Ui.button(this, t.label, bg, if (t.red) android.graphics.Color.WHITE else Ui.INK, sp = 24f) { t.open() }.apply {
                    minHeight = dp(150)
                    maxLines = 4
                    ellipsize = null
                    setPadding(dp(6), dp(16), dp(6), dp(16))
                    background = Ui.rounded(bg, 22, this@MainActivity, if (t.red) null else Ui.LINE)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = dp(14)
                })
            }
            // Lichá poslední dlaždice: stejná šířka jako ostatní
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
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
            "Až tam budete: Postaráno, Pro rodinu, Dokončit nastavení.")
    }

    /** Nový pomocník Dopisy: rodina to jednou dostane v SMS, senior nemusí nic vysvětlovat. */
    private fun announceLetters() {
        val prefs = Prefs(this)
        val p = getSharedPreferences("dopisy", MODE_PRIVATE)
        if (p.getBoolean("ohlaseno", false) || prefs.familyContacts.isEmpty() || !canSms()) return
        p.edit().putBoolean("ohlaseno", true).apply()
        Family.sms(this, "Postaráno: Na telefonu ${prefs.seniorGenitive} přibyl pomocník Dopisy. Na úvodní obrazovce je dlaždice Dopis: " +
            "stačí namířit telefon na dopis a aplikace nahlas řekne, o co jde, jestli to není podvod a co udělat. " +
            "Tlačítkem Poslat rodině vám přijde SMS se shrnutím dopisu, fotku vám může poslat přes WhatsApp. Podezřelý dopis vám pošle sama.", maxParts = 3)
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
        /** Zvýšit, když se změní, co se posílá ven. Senior pak souhlasí znovu. */
        const val CONSENT_VERSION = 4
        /** Na co mám nárok (zapnuto / vypnuto přes Care.enabled). */
        const val BENEFITS = "na"
        private const val DAY = 24L * 3600 * 1000
    }
}
