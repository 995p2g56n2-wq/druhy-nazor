package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Průvodce zapnutím ochrany: jedna obrazovka, jedna věc, velké tlačítko Dál.
 * Hlas čte nahlas, co se děje. Hotové a nepotřebné kroky přeskakuje
 * (český hlas jen když chybí, WhatsApp a Messenger jen když jsou v telefonu).
 * Rodina se vybírá už před průvodcem, tady jsou jen povolení.
 */
class DnSetupActivity : Activity() {

    private class Step(
        val id: String,
        val icon: String,
        val title: String,
        /** Proč to aplikace potřebuje. Čte se nahlas. */
        val why: String,
        /** Kam ťuknout v okénku telefonu. */
        val hint: String,
        val note: String? = null,
        /** Nápis na velkém tlačítku. */
        val button: String = "Dál",
    )

    private val steps = listOf(
        Step("voice", "🗣️", "Český hlas",
            "Telefon zatím neumí mluvit česky. Mám nastavit český hlas?",
            "Objeví se Obchod Play nebo nabídka hlasů. Ťukněte na Instalovat u češtiny a vraťte se šipkou zpět.",
            button = "Ano, nastavit"),
        Step("perms", "✉️", "Zprávy, hovory a upozornění",
            "Aby aplikace viděla příchozí SMS a hovory a mohla vás včas varovat.",
            "Objeví se několik okének. Pokaždé ťukněte na Povolit."),
        Step("sms", "💬", "Aplikace na zprávy",
            "SMS bude otevírat Postaráno místo aplikace Zprávy. Podvod tak zachytí dřív, než ho otevřete.",
            "Ťukněte na Postaráno a potom na Nastavit jako výchozí."),
        Step("uklid", "✉️", "Jedny Zprávy",
            "Na plochu dáme novou obálku Zprávy. Ta hlídá podvody. Původní Zprávy pak z plochy schováme, aby se nepletly.",
            "Objeví se okénko. Ťukněte na Přidat."),
        Step("calls", "📞", "Hlídání hovorů",
            "Aby aplikace poznala podezřelý hovor dřív, než ho zvednete.",
            "Ťukněte na Postaráno a potvrďte."),
        Step("overlay", "🚨", "Varování při hovoru",
            "Když volá podvodník, přes celou obrazovku se ukáže velké červené varování.",
            "Najděte Postaráno, zapněte přepínač a vraťte se šipkou zpět."),
        Step("fullscreen", "❓", "Otázka po hovoru",
            "Po delším hovoru s neznámým číslem se aplikace zeptá, jestli po vás někdo nechtěl peníze.",
            "Zapněte přepínač u Postaráno a vraťte se šipkou zpět."),
        Step("chats", "🟢", "WhatsApp a Messenger",
            "Aplikace zkontroluje i zprávy z WhatsAppu a Messengeru.",
            "Najděte Postaráno, zapněte ho a vraťte se šipkou zpět.",
            note = "Je-li přepínač šedý („Omezené nastavení“): Nastavení → Aplikace → Postaráno → ⋮ vpravo nahoře → Povolit omezená nastavení. Pak to zkuste znovu."),
    )

    /** Kroky, které na začátku průvodce ještě nebyly hotové. */
    private lateinit var todo: List<Step>
    /** -1 = úvod, 0..n-1 = krok, n = konec. */
    private var index = -1
    private var awaiting = false
    private var failed = false
    private var spokenFor = Int.MIN_VALUE
    private var ready = false
    /** Krok Zprávy: obálka už je na ploše, teď schovat původní ikonu. */
    private var uklidPinned = false
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        speaker = Speaker(this)
        setContentView(Ui.column(this, 20, 18).apply {
            setBackgroundColor(Ui.BG)
            addView(Ui.text(this@DnSetupActivity, "Chvilku…", 24f, center = true))
        })
        // Nejdřív zjistit, jestli telefon umí česky, pak teprve vybrat kroky
        Voice.check(this) { ok ->
            Prefs(this).voiceOk = ok
            todo = steps.filter { !isDone(this, it.id) }
            index = -1
            ready = true
            if (todo.isEmpty()) { setResult(RESULT_OK); finish() } else if (!isFinishing) render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (permsGranted(this)) GuardService.start(this)
        if (!ready) return
        evaluate()
        render()
    }

    override fun onDestroy() {
        speaker.stop()
        super.onDestroy()
    }

    /** Po návratu z okénka telefonu: povedlo se → další krok, jinak nabídnout znovu. */
    private fun evaluate() {
        if (!awaiting) return
        awaiting = false
        val step = todo.getOrNull(index) ?: return
        if (step.id == "voice") {
            // Hlas se kontroluje chvilku, výsledek přijde později
            Voice.check(this) { ok ->
                Prefs(this).voiceOk = ok
                if (todo.getOrNull(index)?.id != "voice") return@check
                if (ok) { failed = false; index++ } else failed = true
                if (!isFinishing) render()
            }
            return
        }
        if (isDone(this, step.id)) { failed = false; index++ } else failed = true
    }

    private fun next() { failed = false; index++; render() }

    private fun render() {
        if (index >= todo.size) { setResult(RESULT_OK); finish(); return }
        val root = Ui.column(this, 20, 18).apply { setBackgroundColor(Ui.BG) }
        when {
            index < 0 -> intro(root)
            else -> stepScreen(root, todo[index])
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun speakOnce(text: String) {
        if (spokenFor == index) return
        // Bez českého hlasu by telefon četl česky anglickým hlasem
        if (!Prefs(this).voiceOk) return
        spokenFor = index
        speaker.say(text)
    }

    private fun sayBox(text: String): View {
        val box = Ui.card(this, 8).apply { background = Ui.rounded(Ui.ACCENT_SOFT, 18, this@DnSetupActivity) }
        box.addView(Ui.text(this, text, 20f))
        box.addView(Ui.button(this, "🔊  Přečíst nahlas", Ui.CARD, Ui.ACCENT, sp = 17f) { speaker.say(text) })
        return box
    }

    private fun bigIcon(icon: String) = Ui.text(this, icon, 64f, center = true).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun intro(root: LinearLayout) {
        root.addView(bigIcon("🛡️"))
        root.addView(Ui.text(this, "Zapneme ochranu", 30f, bold = true, center = true))
        val n = todo.size
        val t = when (n) {
            1 -> "Zbývá jedna věc. Stačí ťuknout na velké tlačítko Dál."
            in 2..4 -> "Zbývají $n věci. Projdeme je spolu, jednu po druhé. Stačí ťukat na velké tlačítko Dál."
            else -> "Zbývá $n věcí. Projdeme je spolu, jednu po druhé. Stačí ťukat na velké tlačítko Dál."
        }
        root.addView(sayBox(t))
        speakOnce(t)
        root.addView(Ui.button(this, "Dál") { next() })
    }

    private fun stepScreen(root: LinearLayout, step: Step) {
        if (step.id == "uklid" && uklidPinned) { uklidScreen(root); return }
        root.addView(Ui.text(this, "Nastavení ${index + 1} z ${todo.size}", 17f, color = Ui.MUTED))
        root.addView(bigIcon(step.icon))
        root.addView(Ui.text(this, step.title, 28f, bold = true, center = true))
        if (failed) {
            val t = "Ještě to není zapnuté. Zkusíme to znovu? ${step.hint}"
            root.addView(sayBox(t))
            speakOnce(t)
            step.note?.let { root.addView(Ui.text(this, it, 16f, color = Ui.MUTED)) }
            root.addView(Ui.button(this, "Zkusit znovu") { act(step) })
            root.addView(later { next() })
        } else {
            val t = "${step.why} Teď se objeví okénko telefonu. ${step.hint}"
            root.addView(sayBox(t))
            speakOnce(t)
            root.addView(Ui.button(this, step.button) { act(step) })
            if (step.id == "voice") {
                root.addView(later("Ne, bez hlasu") { next() })
            }
        }
    }

    /** Druhá půlka kroku Zprávy: schovat původní ikonu z plochy. Aplikace to sama neumí. */
    private fun uklidScreen(root: LinearLayout) {
        root.addView(Ui.text(this, "Nastavení ${index + 1} z ${todo.size}", 17f, color = Ui.MUTED))
        root.addView(bigIcon("✉️"))
        root.addView(Ui.text(this, "Schovat původní Zprávy", 28f, bold = true, center = true))
        val t = "Na ploše podržte prst na staré ikoně Zprávy. Ťukněte na Odebrat. Nic se nesmaže, ikona jen zmizí z plochy."
        root.addView(sayBox(t))
        speakOnce(t)
        root.addView(Ui.button(this, "Hotovo, je schovaná") {
            Prefs(this).uklidDone = true
            uklidPinned = false
            next()
        })
        root.addView(later { uklidPinned = false; next() })
    }

    /** Malé nenápadné tlačítko dole. Hlavní cesta je vždy velké tlačítko nad ním. */
    private fun later(label: String = "Později", onClick: () -> Unit) =
        Ui.button(this, label, Ui.BG, Ui.MUTED, sp = 16f) { onClick() }

    /** Dá na plochu obálku Zprávy, která otevře naše zprávy. */
    private fun pinMessages(): Boolean {
        val sm = getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val open = Intent(this, DruhyNazorActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true)
        val info = ShortcutInfo.Builder(this, "zpravy")
            .setShortLabel("Zprávy")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_envelope))
            .setIntent(open)
            .build()
        return try { sm.requestPinShortcut(info, null) } catch (e: Exception) { false }
    }

    private fun act(step: Step) {
        speaker.stop()
        awaiting = true
        spokenFor = Int.MIN_VALUE
        when (step.id) {
            "voice" -> Voice.fix(this)
            "uklid" -> { awaiting = false; pinMessages(); uklidPinned = true; render() }
            "perms" -> requestPermissions(perms(), 10)
            "sms" -> requestRole(RoleManager.ROLE_SMS)
            "calls" -> requestRole(RoleManager.ROLE_CALL_SCREENING)
            "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else { awaiting = false; next() }
            "chats" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestRole(role: String) {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(role)) {
            awaiting = false
            Toast.makeText(this, "Tento telefon to nepodporuje, jdeme dál.", Toast.LENGTH_LONG).show()
            next(); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(role), 11)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permsGranted(this)) GuardService.start(this)
        evaluate(); render()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        evaluate(); render()
    }

    companion object {
        fun perms(): Array<String> {
            val list = mutableListOf(
                Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            return list.toTypedArray()
        }

        fun permsGranted(ctx: Context) = perms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        private fun roleHeld(ctx: Context, role: String): Boolean {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
            return !rm.isRoleAvailable(role) || rm.isRoleHeld(role)
        }

        fun isDone(ctx: Context, id: String): Boolean = when (id) {
            "perms" -> permsGranted(ctx)
            "sms" -> roleHeld(ctx, RoleManager.ROLE_SMS)
            "calls" -> roleHeld(ctx, RoleManager.ROLE_CALL_SCREENING)
            "overlay" -> ScamCallActivity.canShow(ctx)
            "fullscreen" -> Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            "chats" -> !ChatWatcher.anyChatInstalled(ctx) || ChatWatcher.isEnabled(ctx)
            "voice" -> Prefs(ctx).voiceOk
            "uklid" -> Prefs(ctx).uklidDone
            else -> true
        }

        /** Všechno potřebné je zapnuté. */
        fun allDone(ctx: Context) =
            listOf("perms", "sms", "calls", "overlay", "fullscreen", "chats").all { isDone(ctx, it) } && GuardService.isRunning(ctx)
    }
}
