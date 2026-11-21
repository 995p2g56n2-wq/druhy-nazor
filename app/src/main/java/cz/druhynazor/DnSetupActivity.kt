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
        /** Proč to potřebujeme. Čte se nahlas. */
        val why: String,
        /** Kam ťuknout v okénku telefonu. */
        val hint: String,
        val note: String? = null,
        /** Nápis na velkém tlačítku. */
        val button: String = "Dál",
    )

    /**
     * Aplikace stažená mimo Obchod Play nedostane na Samsungu ikonu na plochu sama.
     * Pak ji dáme na plochu my, spolu s obálkou Zprávy.
     */
    private val needsAppIcon by lazy {
        val src = try {
            if (Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName
            else oldInstaller()
        } catch (e: Exception) { null }
        src != "com.android.vending"
    }

    @Suppress("DEPRECATION")
    private fun oldInstaller(): String? = packageManager.getInstallerPackageName(packageName)

    /**
     * Každý krok: proč (jedna věta), co udělat v okénku telefonu, a jako poslední věta
     * vždy to, co udělat teď: „Teď ťukněte dole na …“. Nejvýš 3 věty, bez slova „aplikace“.
     */
    private val steps by lazy { listOf(
        Step("voice", "🗣️", "Český hlas",
            "Telefon zatím neumí mluvit česky.",
            "V okénku pak u češtiny ťukněte na Instalovat a vraťte se šipkou zpět.",
            button = "Ano, nastavit"),
        Step("perms", "✉️", "Zprávy a hovory",
            "Smím hlídat vaše zprávy a hovory, abych vás včas varovala?",
            "V každém okénku pak ťukněte na Povolit."),
        Step("sms", "💬", "Zprávy přes Postaráno",
            "Zprávy budu otevírat já, abych podvod zachytila dřív než vy.",
            "V okénku pak ťukněte na Postaráno a na Nastavit jako výchozí."),
        if (needsAppIcon) Step("uklid", "✉️", "Ikony na plochu",
            "Na plochu dám štít Postaráno a obálku Zprávy, která hlídá podvody.",
            "Ukážou se dvě okénka a v obou ťukněte na Přidat.")
        else Step("uklid", "✉️", "Obálka na plochu",
            "Na plochu dám obálku Zprávy, která hlídá podvody.",
            "V okénku pak ťukněte na Přidat."),
        Step("calls", "📞", "Hlídání hovorů",
            "Smím hlídat hovory, abych poznala podvodníka dřív, než zvednete?",
            "V okénku pak ťukněte na Postaráno a potvrďte."),
        Step("overlay", "🚨", "Varování při hovoru",
            "Když volá podvodník, ukážu přes celou obrazovku červené varování.",
            "V seznamu pak najděte Postaráno a zapněte přepínač. Sem se vrátím sám."),
        Step("chats", "🟢", "WhatsApp a Messenger",
            "Budu hlídat i WhatsApp a Messenger.",
            "Pak zapněte přepínač a potvrďte Povolit. Sem se vrátím sám.",
            note = "Je-li přepínač šedý („Omezené nastavení“): Nastavení → Aplikace → Postaráno → ⋮ vpravo nahoře → Povolit omezená nastavení. Pak to zkuste znovu."),
    ) }

    /** Kroky, které na začátku průvodce ještě nebyly hotové. */
    private lateinit var todo: List<Step>
    /** -1 = úvod, 0..n-1 = krok, n = konec. */
    private var index = -1
    private var awaiting = false
    private var failed = false
    private var spokenFor = Int.MIN_VALUE
    private var ready = false
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

    /** Aplikaci zakrylo okénko telefonu (třeba „Přidat na plochu“). */
    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
        speaker.stop()
    }

    override fun onResume() {
        super.onResume()
        watcher.removeCallbacksAndMessages(null)
        paused = false
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
        // Zavřelo se okénko „Přidat na plochu“: další okénko, nebo další krok
        if (step.id == "uklid") { pinNext(); return }
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
        // Čeká se na okénko telefonu: nemluvit do něj
        if (awaiting) return
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
            1 -> "Zbývá jedna věc. Teď ťukněte dole na Dál."
            in 2..4 -> "Zbývají $n věci, projdeme je spolu. Teď ťukněte dole na Dál."
            else -> "Zbývá $n věcí, projdeme je spolu. Teď ťukněte dole na Dál."
        }
        root.addView(sayBox(t))
        speakOnce(t)
        root.addView(Ui.button(this, "Dál") { next() })
    }

    private fun stepScreen(root: LinearLayout, step: Step) {
        root.addView(Ui.text(this, "Nastavení ${index + 1} z ${todo.size}", 17f, color = Ui.MUTED))
        root.addView(bigIcon(step.icon))
        root.addView(Ui.text(this, step.title, 28f, bold = true, center = true))
        if (failed) {
            val t = "Ještě to není zapnuté. ${step.hint} Teď ťukněte dole na Zkusit znovu."
            root.addView(sayBox(t))
            speakOnce(t)
            step.note?.let { root.addView(Ui.text(this, it, 16f, color = Ui.MUTED)) }
            root.addView(Ui.button(this, "Zkusit znovu") { act(step) })
            root.addView(later { next() })
        } else {
            val t = "${step.why} ${step.hint} Teď ťukněte dole na ${step.button}."
            root.addView(sayBox(t))
            speakOnce(t)
            root.addView(Ui.button(this, step.button) { act(step) })
            if (step.id == "voice") {
                root.addView(later("Ne, bez hlasu") { next() })
            }
        }
    }

    /** Malé nenápadné tlačítko dole. Hlavní cesta je vždy velké tlačítko nad ním. */
    private fun later(label: String = "Později", onClick: () -> Unit) =
        Ui.button(this, label, Ui.BG, Ui.MUTED, sp = 16f) { onClick() }

    /** Co ještě dát na plochu: "app" = štít Postaráno, "zpravy" = obálka Zprávy. */
    private val pinQueue = ArrayDeque<String>()
    /** Číslo posledního okénka, aby starý odpočet neposunul průvodce u dalšího okénka. */
    private var pinToken = 0

    /** Ukáže další okénko „Přidat na plochu“ a počká, až ho člověk zavře. Nakonec jde na další krok. */
    private fun pinNext() {
        val what = pinQueue.removeFirstOrNull()
        if (what == null) { awaiting = false; next(); return }
        paused = false
        awaiting = true
        if (!pin(what)) { pinNext(); return }
        // Po zavření okénka (Přidat i Zrušit) přijde onResume → evaluate → další okénko nebo krok.
        // Kdyby se okénko vůbec neukázalo, po chvilce jít dál samo.
        val token = ++pinToken
        window.decorView.postDelayed({
            if (token == pinToken && awaiting && !paused && !isFinishing) pinNext()
        }, 2500)
    }

    private fun pin(what: String): Boolean = pinShortcut(this, what)

    private fun act(step: Step) {
        speaker.stop()
        awaiting = true
        spokenFor = Int.MIN_VALUE
        when (step.id) {
            "voice" -> Voice.fix(this)
            // Jen obálka na plochu. Schování původních Zpráv je mimo aplikaci, to udělá rodina
            // později podle návodu v Pro rodinu (senior by se z plochy zpátky nevrátil).
            "uklid" -> {
                Prefs(this).envelopePinned = true
                pinQueue.clear()
                if (needsAppIcon) pinQueue.addLast("app")
                pinQueue.addLast("zpravy")
                pinNext()
            }
            "perms" -> requestPermissions(perms(), 10)
            "sms" -> requestRole(RoleManager.ROLE_SMS)
            "calls" -> requestRole(RoleManager.ROLE_CALL_SCREENING)
            "overlay" -> {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                watchAndReturn(step.id)
            }
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else { awaiting = false; next() }
            "chats" -> {
                // Rovnou na přepínač Postaráno, ne na seznam všech aplikací
                val direct = if (Build.VERSION.SDK_INT >= 30)
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        android.content.ComponentName(this, ChatWatcher::class.java).flattenToString())
                else null
                try { startActivity(direct ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                watchAndReturn(step.id)
            }
        }
    }

    private val watcher = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Přepínač v nastavení telefonu: jakmile ho člověk zapne, vrátíme se sami.
     * Nikdo nemusí hledat šipku zpět.
     */
    private fun watchAndReturn(id: String) {
        watcher.removeCallbacksAndMessages(null)
        val until = System.currentTimeMillis() + 3 * 60_000
        val check = object : Runnable {
            override fun run() {
                if (!paused || isFinishing) return
                if (isDone(this@DnSetupActivity, id)) {
                    try {
                        startActivity(Intent(this@DnSetupActivity, DnSetupActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (_: Exception) { }
                    return
                }
                if (System.currentTimeMillis() < until) watcher.postDelayed(this, 600)
            }
        }
        watcher.postDelayed(check, 1000)
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
        /** Dá na plochu štít Postaráno nebo obálku Zprávy. */
        fun pinShortcut(ctx: Context, what: String): Boolean {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
            if (!sm.isRequestPinShortcutSupported) return false
            val info = if (what == "app") {
                ShortcutInfo.Builder(ctx, "postarano")
                    .setShortLabel("Postaráno")
                    .setIcon(Icon.createWithResource(ctx, R.drawable.ic_shield))
                    .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .build()
            } else {
                ShortcutInfo.Builder(ctx, "zpravy")
                    .setShortLabel("Zprávy")
                    .setIcon(Icon.createWithResource(ctx, R.drawable.ic_envelope))
                    .setIntent(Intent(ctx, DruhyNazorActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true))
                    .build()
            }
            return try { sm.requestPinShortcut(info, null) } catch (e: Exception) { false }
        }

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
            "uklid" -> Prefs(ctx).envelopePinned || Prefs(ctx).uklidDone
            else -> true
        }

        // „fullscreen“ (otázka po hovoru přes celou obrazovku) už není potřeba zvlášť:
        // se svolením „overlay“ smí Postaráno otázku po hovoru otevřít rovnou.
        private val REQUIRED = listOf("perms", "sms", "calls", "overlay", "chats")

        /** Lidské názvy kroků pro obrazovku Pro rodinu. */
        private val TITLES = mapOf(
            "perms" to "Zprávy, hovory a upozornění",
            "sms" to "Postaráno jako aplikace na zprávy",
            "calls" to "Hlídání hovorů",
            "overlay" to "Varování přes celou obrazovku při hovoru",
            "chats" to "Hlídání WhatsAppu a Messengeru",
        )

        /** Co přesně chybí, aby ochrana byla celá. Prázdné = všechno zapnuté. */
        fun missing(ctx: Context): List<String> {
            val list = REQUIRED.filter { !isDone(ctx, it) }.map { TITLES.getValue(it) }.toMutableList()
            if (permsGranted(ctx) && !GuardService.isRunning(ctx)) list += "Hlídání na pozadí se nespustilo (pomůže restart telefonu)"
            return list
        }

        /** Chybí hlídání chatů (u aplikace stažené mimo Obchod Play ho Android často zamkne). */
        fun chatsMissing(ctx: Context) = !isDone(ctx, "chats")

        /** Všechno potřebné je zapnuté. */
        fun allDone(ctx: Context) =
            REQUIRED.all { isDone(ctx, it) } && GuardService.isRunning(ctx)
    }
}
