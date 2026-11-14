package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Průvodce zapnutím Druhého názoru: jedna obrazovka, jedna věc, velké tlačítko Dál.
 * Hlas čte nahlas, co se děje. Hotové kroky přeskakuje.
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
    )

    private val steps = listOf(
        Step("perms", "✉️", "Zprávy, hovory a upozornění",
            "Aby aplikace viděla příchozí SMS a hovory a mohla vás včas varovat.",
            "Objeví se několik okének. Pokaždé ťukněte na Povolit."),
        Step("sms", "💬", "Aplikace na zprávy",
            "SMS bude otevírat Postaráno místo aplikace Zprávy. Podvod tak zachytí dřív, než ho otevřete.",
            "Ťukněte na Postaráno a potom na Nastavit jako výchozí."),
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
    /** -1 = úvod, 0..n-1 = krok, n = rodina. */
    private var index = -1
    private var awaiting = false
    private var failed = false
    private var spokenFor = Int.MIN_VALUE
    private var familyShown = false
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        speaker = Speaker(this)
        todo = steps.filter { !isDone(this, it.id) }
        index = if (todo.isEmpty()) todo.size else -1
    }

    override fun onResume() {
        super.onResume()
        if (permsGranted(this)) GuardService.start(this)
        evaluate()
        // Obrazovku s rodinou nepřekreslovat, aby nezmizelo rozepsané jméno nebo číslo.
        if (!(index >= todo.size && familyShown)) render()
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
        if (isDone(this, step.id)) { failed = false; index++ } else failed = true
    }

    private fun next() { failed = false; index++; render() }

    private fun render() {
        val root = Ui.column(this, 20, 18).apply { setBackgroundColor(Ui.BG) }
        when {
            index < 0 -> intro(root)
            index < todo.size -> stepScreen(root, todo[index])
            else -> family(root)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun speakOnce(text: String) {
        if (spokenFor == index) return
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
        root.addView(Ui.text(this, "Zapneme Druhý názor", 30f, bold = true, center = true))
        val t = "Telefon se teď zeptá na ${todo.size} povolení. Projdeme je spolu, jedno po druhém. Stačí ťukat na velké tlačítko Dál."
        root.addView(sayBox(t))
        speakOnce(t)
        root.addView(Ui.button(this, "Dál") { next() })
    }

    private fun stepScreen(root: LinearLayout, step: Step) {
        root.addView(Ui.text(this, "Nastavení ${index + 1} z ${todo.size}", 17f, color = Ui.MUTED))
        root.addView(bigIcon(step.icon))
        root.addView(Ui.text(this, step.title, 28f, bold = true, center = true))
        if (failed) {
            val t = "Ještě to není zapnuté. Zkusíme to znovu? ${step.hint}"
            root.addView(sayBox(t))
            speakOnce(t)
            step.note?.let { root.addView(Ui.text(this, it, 16f, color = Ui.MUTED)) }
            root.addView(Ui.button(this, "Zkusit znovu") { act(step) })
            root.addView(Ui.button(this, "Přeskočit, zapnu to později", Ui.CARD, Ui.ACCENT, sp = 18f) { next() })
        } else {
            val t = "${step.why} Teď se objeví okénko telefonu. ${step.hint}"
            root.addView(sayBox(t))
            speakOnce(t)
            root.addView(Ui.button(this, "Dál") { act(step) })
        }
    }

    private fun act(step: Step) {
        speaker.stop()
        awaiting = true
        spokenFor = Int.MIN_VALUE
        when (step.id) {
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

    private fun family(root: LinearLayout) {
        familyShown = true
        root.addView(bigIcon("👨‍👩‍👧"))
        root.addView(Ui.text(this, "Propojit s rodinou", 28f, bold = true, center = true))
        val t = "Vyberte, komu z rodiny má aplikace napsat, když přijde něco podezřelého. Rodina nemusí nic instalovat."
        root.addView(sayBox(t))
        speakOnce(t)
        root.addView(FamilyUi.card(this) { render() })
        root.addView(Ui.button(this, "Hotovo") {
            if (Prefs(this).familyContacts.isEmpty()) {
                Toast.makeText(this, "Přidejte aspoň jednoho člověka z rodiny.", Toast.LENGTH_LONG).show()
            } else { setResult(RESULT_OK); finish() }
        })
        root.addView(Ui.button(this, "Přeskočit, nastavím později", Ui.BG, Ui.MUTED, sp = 17f) { setResult(RESULT_OK); finish() })
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
        if (FamilyUi.onResult(this, requestCode, resultCode, data)) { render(); return }
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
            "chats" -> ChatWatcher.isEnabled(ctx)
            else -> true
        }

        /** Všechno potřebné je zapnuté. */
        fun allDone(ctx: Context) =
            listOf("perms", "sms", "calls", "overlay", "fullscreen", "chats").all { isDone(ctx, it) } && GuardService.isRunning(ctx)
    }
}
