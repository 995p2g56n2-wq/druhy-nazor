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
            "V okénku pak u češtiny stiskněte Instalovat a vraťte se šipkou vlevo nahoře.",
            button = "Ano, nastavit"),
        Step("sms", "💬", "Zprávy přes Postaráno",
            "Zprávy budu otevírat já, abych podvod zachytila dřív než vy.",
            "V okénku pak vyberte Postaráno a stiskněte Změnit na výchozí."),
        Step("perms", "✉️", "Zprávy a hovory",
            "Budu hlídat vaše zprávy a hovory, abych vás včas varovala.",
            "Ukážou se okénka pro oznámení, hovory a SMS. V každém stiskněte Povolit."),
        if (needsAppIcon) Step("uklid", "✉️", "Ikony na plochu",
            "Na plochu dám štít Postaráno a obálku Zprávy, která hlídá podvody.",
            "Ukážou se dvě okénka a v obou zmáčkněte Přidat.")
        else Step("uklid", "✉️", "Obálka na plochu",
            "Na plochu dám obálku Zprávy, která hlídá podvody.",
            "V okénku pak zmáčkněte Přidat."),
        Step("calls", "📞", "Hlídání hovorů",
            "Budu hlídat hovory, abych poznala podvodníka dřív, než zvednete telefon.",
            "V okénku pak vyberte Postaráno a vpravo dole stiskněte Změnit na výchozí."),
        Step("overlay", "🚨", "Varování při hovoru",
            "Když volá podvodník, ukážu přes celou obrazovku červené varování.",
            "Ukáže se dlouhý seznam aplikací. V klidu v něm najděte Postaráno, klidně posuňte prstem, a zapněte přepínač vpravo."),
        Step("chats", "🟢", "WhatsApp a Messenger",
            "Budu hlídat i WhatsApp a Messenger.",
            "Ukáže se dlouhý seznam aplikací. V klidu v něm najděte Postaráno, klidně posuňte prstem, zapněte přepínač vpravo a stiskněte Povolit.",
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
        BackGuide.uiVisible = false
        // Nezastavovat hlas: právě říká, kam v okénku telefonu ťuknout.
    }

    /** Co přesně ťuknout v okénku telefonu. Ukáže se nahoře nad okénkem a řekne se nahlas, když se okénko otevře. */
    private var dialogHint: String? = null

    /**
     * Pokyny do okénka telefonu, v pořadí ťuknutí. Slovesa se střídají (ťukněte, stiskněte,
     * zmáčkněte, vyberte). Nikdy nepředbíhat: další pokyn až podle toho, co se v telefonu
     * opravdu změnilo (povolení, přepínač). Kde změnu poznat nejde (jedno okénko se dvěma
     * ťuknutími), řekne se obojí najednou a opakuje se celé, nikdy jen ta druhá půlka.
     */
    private fun dialogSteps(id: String): List<String> = when (id) {
        "voice" -> listOf("U češtiny stiskněte Instalovat.", "Až se stáhne, zmáčkněte šipku vlevo nahoře.")
        // Android každou skupinu povolení ukáže ve vlastním okénku, sloučit je nejde.
        // Aspoň dopředu říct, kolik jich bude, a vypsat je na jednom místě.
        "perms" -> listOf(permsLeftText(), "V každém stiskněte Povolit.")
        "sms", "calls" -> listOf("Vyberte Postaráno.", "Potom vpravo dole stiskněte Změnit na výchozí.")
        "uklid" -> listOf("Zmáčkněte Přidat.")
        // Seznam aplikací je dlouhý a senior v něm Postaráno snadno přehlédne: polopatě, jak hledat a posunout
        "overlay" -> listOf(
            "Teď uvidíte seznam. Každý řádek je jedna aplikace.",
            "V klidu řádky projděte a najděte Postaráno. Když ho nevidíte, přejeďte prstem po obrazovce zdola nahoru.",
            "Na Postaráno ťukněte.",
            "Potom zmáčkněte přepínač vpravo, aby svítil modře.")
        "chats" -> listOf(
            "Teď uvidíte seznam. Každý řádek je jedna aplikace.",
            "V klidu řádky projděte a najděte Postaráno. Když ho nevidíte, přejeďte prstem po obrazovce zdola nahoru.",
            "U Postaráno vpravo zmáčkněte přepínač, aby svítil modře.",
            "Potom stiskněte Povolit.")
        else -> listOf("Stiskněte Povolit.")
    }

    /** Kolik okének Povolit ještě přijde a pro co (oznámení, hovory, SMS). */
    private fun permsLeftText(): String {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val groups = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.POST_NOTIFICATIONS in missing) groups += "oznámení"
        if (Manifest.permission.READ_PHONE_STATE in missing) groups += "telefonní hovory"
        if (missing.any { it.contains("SMS") }) groups += "SMS"
        return when (groups.size) {
            0, 1 -> "Ukáže se okénko" + (groups.firstOrNull()?.let { " pro $it" } ?: "") + "."
            2 -> "Ukážou se dvě okénka: ${groups[0]} a ${groups[1]}."
            else -> "Ukážou se tři okénka: ${groups[0]}, ${groups[1]} a ${groups[2]}."
        }
    }

    private val sayer = android.os.Handler(android.os.Looper.getMainLooper())

    private fun showDialogHint(id: String) =
        // U dlouhého seznamu aplikací opakovat častěji a déle, hledání chvíli trvá
        if (id == "overlay" || id == "chats") guide(dialogSteps(id), repeatMs = 20_000, times = 6)
        else guide(dialogSteps(id))

    /** Nahoře velký pokyn a říct ho nahlas; když se nic nezmění, po chvíli ho zopakovat (ten samý). */
    private fun guide(lines: List<String>, repeatMs: Long = 15_000, times: Int = 3) {
        dialogHint = if (lines.size == 1) lines[0] else lines.mapIndexed { i, t -> "${i + 1}.  $t" }.joinToString("\n\n")
        render()
        sayer.removeCallbacksAndMessages(null)
        if (!Prefs(this).voiceOk) return
        val text = lines.joinToString(" ")
        var n = 0
        val say = object : Runnable {
            override fun run() {
                if (!awaiting || BackGuide.active) return
                speaker.say(text)
                if (++n < times) sayer.postDelayed(this, repeatMs)
            }
        }
        sayer.postDelayed(say, 300)
    }

    override fun onResume() {
        super.onResume()
        BackGuide.uiVisible = true
        // Hlídání okének (watchState) se zastaví samo, až evaluate() ukončí čekání.
        paused = false
        if (permsGranted(this)) GuardService.start(this)
        if (!ready) return
        evaluate()
        render()
    }

    override fun onDestroy() {
        sayer.removeCallbacksAndMessages(null)
        watcher.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    /** Po návratu z okénka telefonu: povedlo se → další krok, jinak nabídnout znovu. */
    private fun evaluate() {
        dialogHint = null
        sayer.removeCallbacksAndMessages(null)
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
        // Krok, který se mezitím udělal sám, přeskočit: výchozí Zprávy dostanou od telefonu
        // povolení ke zprávám, hovorům i oznámením, takže okénka Povolit pak už nejsou potřeba.
        if (!awaiting && !failed && index >= 0)
            while (index < todo.size && todo[index].id != "uklid" && isDone(this, todo[index].id)) index++
        if (index >= todo.size) { setResult(RESULT_OK); finish(); return }
        val root = Ui.column(this, 20, 18).apply { setBackgroundColor(Ui.BG) }
        val hint = dialogHint
        if (awaiting && hint != null) {
            // Okénko telefonu zakrývá spodek obrazovky: pokyn jen nahoře, velkým písmem
            val box = Ui.card(this, 8).apply { background = Ui.rounded(Ui.ACCENT_SOFT, 18, this@DnSetupActivity) }
            box.addView(Ui.text(this, hint, 28f, bold = true))
            root.addView(box)
            setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
            return
        }
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
        root.addView(Ui.text(this, "👇", 44f, center = true))
        root.addView(Ui.button(this, "Dál") { next() })
    }

    private fun stepScreen(root: LinearLayout, step: Step) {
        root.addView(Ui.text(this, "Nastavení ${index + 1} z ${todo.size}", 17f, color = Ui.MUTED))
        root.addView(bigIcon(step.icon))
        root.addView(Ui.text(this, step.title, 28f, bold = true, center = true))
        if (failed) {
            val t = "Ještě to není zapnuté. ${step.hint} Teď zmáčkněte dole Zkusit znovu."
            root.addView(sayBox(t))
            speakOnce(t)
            step.note?.let { root.addView(Ui.text(this, it, 16f, color = Ui.MUTED)) }
            root.addView(Ui.text(this, "👇", 44f, center = true))
            root.addView(Ui.button(this, "Zkusit znovu") { act(step) })
            root.addView(later { next() })
        } else {
            // Co ťuknout v okénku telefonu řekneme až ve chvíli, kdy se okénko otevře
            val press = listOf("ťukněte dole na", "stiskněte dole tlačítko", "zmáčkněte dole tlačítko")[index.coerceAtLeast(0) % 3]
            val t = "${step.why} Pak vám řeknu, co dál. Teď $press ${step.button}."
            root.addView(sayBox(t))
            speakOnce(t)
            root.addView(Ui.text(this, "👇", 44f, center = true))
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
        showDialogHint("uklid")
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
            }
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else { awaiting = false; next() }
            "chats" -> {
                BackGuide.expect(this)
                // Rovnou na přepínač Postaráno, ne na seznam všech aplikací
                val direct = if (Build.VERSION.SDK_INT >= 30)
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        android.content.ComponentName(this, ChatWatcher::class.java).flattenToString())
                else null
                try { startActivity(direct ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        // Otevřelo se okénko telefonu (u ikon na plochu to dělá pinNext)
        if (awaiting && step.id != "uklid") {
            showDialogHint(step.id)
            if (step.id in setOf("perms", "overlay", "chats")) watchState(step.id)
        }
    }

    private val watcher = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Hlídá, co se v telefonu opravdu změnilo, a podle toho řekne další pokyn.
     * - Povolení: každé ťuknutí na Povolit je hned vidět, tak se řekne „zase Povolit“ až po něm.
     * - Přepínač: jakmile svítí, zkusíme se vrátit sami; když to telefon nedovolí, navedeme
     *   na šipku vlevo nahoře (a znovu, když se objeví ještě jeden seznam).
     */
    private fun watchState(id: String) {
        watcher.removeCallbacksAndMessages(null)
        val until = System.currentTimeMillis() + 5 * 60_000
        var granted = if (id == "perms") perms().count { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } else 0
        var doneAt = 0L
        var backSaid = 0
        val check = object : Runnable {
            override fun run() {
                if (!awaiting || isFinishing || System.currentTimeMillis() > until) return
                if (id == "perms") {
                    val now = perms().count { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                    if (now > granted && now < perms().size) guide(listOf("Výborně. Teď zase stiskněte Povolit."))
                    granted = now
                } else if (paused && isDone(this@DnSetupActivity, id)) {
                    val t = System.currentTimeMillis()
                    // Hotovo: hned přestat opakovat původní pokyn („najděte Postaráno…“)
                    if (doneAt == 0L) { doneAt = t; sayer.removeCallbacksAndMessages(null); comeBack() }
                    // U WhatsAppu vyvádí ven BackGuide z ChatWatcher (funguje, i když telefon
                    // aplikaci ukončí). Když se do 3 s neozval, navedeme na šipku sami.
                    else if (id == "chats" && (BackGuide.active || t - doneAt < 3000)) { }
                    else if (t - doneAt > 1500 && backSaid == 0) {
                        backSaid = 1
                        guide(listOf("Teď zmáčkněte šipku vlevo nahoře."), repeatMs = 8000, times = 1)
                    } else if (t - doneAt > 1500 + backSaid * 8000L && backSaid < 5) {
                        backSaid++
                        guide(listOf("Zmáčkněte ještě jednou šipku vlevo nahoře."), repeatMs = 8000, times = 1)
                    }
                }
                watcher.postDelayed(this, 400)
            }
        }
        watcher.postDelayed(check, 500)
    }

    /**
     * Vrátit se do průvodce z nastavení telefonu. Od Androidu 15 to jde jen, když je zrovna
     * vidět naše překrytí, proto na okamžik přidáme neviditelný bod přes obrazovku.
     */
    private fun comeBack() {
        val wm = getSystemService(android.view.WindowManager::class.java)
        var dot: View? = null
        if (Settings.canDrawOverlays(this) && wm != null) try {
            dot = View(applicationContext)
            wm.addView(dot, android.view.WindowManager.LayoutParams(1, 1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT))
        } catch (_: Exception) { dot = null }
        try {
            startActivity(Intent(this, DnSetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { }
        dot?.let { v -> watcher.postDelayed({ try { wm?.removeView(v) } catch (_: Exception) { } }, 1500) }
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
