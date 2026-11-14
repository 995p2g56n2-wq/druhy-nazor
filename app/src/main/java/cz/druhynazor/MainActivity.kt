package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp

/**
 * Postaráno – rodinný štít. Úvod (pro koho), katalog pomocníků s tlačítkem Instalovat
 * a úvodní obrazovka s velkými ikonami zapnutých pomocníků.
 */
class MainActivity : Activity() {

    private enum class Screen { START, WHO, CATALOG, INFO, DONE, HOME }

    private var screen = Screen.HOME
    private val selected = mutableSetOf<String>()
    private var showMore = false
    private var infoId = Modules.DN
    private var infoFrom = Screen.CATALOG
    private lateinit var speaker: Speaker

    /** Pro koho se nastavuje: zobrazované jméno a tvar pro „Co chcete … nainstalovat?“ */
    private val who = listOf("Mamka" to "mamce", "Babička" to "babičce", "Taťka" to "taťkovi", "Děda" to "dědovi")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        speaker = Speaker(this)
        screen = if (Modules.onboarded(this)) Screen.HOME else Screen.START
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
        if (Modules.DN in Modules.installed(this) && DnSetupActivity.permsGranted(this)) GuardService.start(this)
        render()
        // Hlídání po spuštění chvíli nabíhá (třeba po aktualizaci), stav pak zkontrolovat znovu
        window.decorView.postDelayed({ if (screen == Screen.HOME && !isFinishing) render() }, 1500)
    }

    override fun onDestroy() {
        speaker.stop()
        super.onDestroy()
    }

    private fun go(s: Screen) { screen = s; render() }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        when (screen) {
            Screen.WHO -> go(Screen.START)
            Screen.CATALOG -> go(if (Modules.onboarded(this)) Screen.HOME else Screen.WHO)
            Screen.INFO -> go(infoFrom)
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
            Screen.WHO -> whoScreen(root)
            Screen.CATALOG -> catalog(root)
            Screen.INFO -> info(root)
            Screen.DONE -> done(root)
            Screen.HOME -> home(root)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun back(root: LinearLayout) {
        root.addView(Ui.button(this, "‹  Zpět", Ui.BG, Ui.ACCENT, sp = 18f) {
            @Suppress("DEPRECATION") onBackPressed()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) })
    }

    private fun spacer(root: LinearLayout, dpH: Int = 24) =
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(dpH)))

    // ---------- Úvod ----------

    private fun start(root: LinearLayout) {
        spacer(root, 40)
        root.addView(Ui.text(this, "🛡️", 72f, center = true))
        root.addView(Ui.text(this, "Postaráno", 36f, bold = true, center = true))
        root.addView(Ui.text(this, "rodinný štít", 20f, color = Ui.ACCENT, center = true))
        root.addView(Ui.text(this, "Jedna aplikace.\nKlidný spánek pro celou rodinu.", 24f, bold = true, center = true))
        root.addView(Ui.text(this, "Vyberete si jen to, co opravdu potřebujete.", 18f, color = Ui.MUTED, center = true))
        spacer(root, 40)
        root.addView(Ui.button(this, "Začít") { go(Screen.WHO) })
    }

    private fun whoScreen(root: LinearLayout) {
        back(root)
        root.addView(Ui.text(this, "Pro koho aplikaci nastavujete?", 28f, bold = true))
        root.addView(choice("Pro sebe") {
            Modules.setForWhom(this, null); go(Screen.CATALOG)
        })
        root.addView(Ui.text(this, "Nebo pro někoho blízkého:", 18f, color = Ui.MUTED))
        for ((name, _) in who) {
            root.addView(choice(name) {
                Modules.setForWhom(this, name)
                Prefs(this).seniorName = name
                go(Screen.CATALOG)
            })
        }
    }

    private fun choice(label: String, onClick: (View) -> Unit) =
        Ui.button(this, "$label   ›", Ui.CARD, Ui.INK, sp = 22f, onClick = onClick)

    // ---------- Katalog ----------

    private fun catalog(root: LinearLayout) {
        back(root)
        val installed = Modules.installed(this)
        val dative = who.firstOrNull { it.first == Modules.forWhom(this) }?.second
        root.addView(Ui.text(this, if (dative != null) "Co chcete $dative nainstalovat?" else "Co chcete nainstalovat?", 28f, bold = true))
        root.addView(Ui.text(this, "Vyberte jednoho nebo více pomocníků. Další můžete kdykoli přidat.", 18f, color = Ui.MUTED))

        for (m in Modules.all.filter { !it.more || showMore }) root.addView(moduleCard(m, installed))

        val hidden = Modules.all.count { it.more }
        if (!showMore) {
            root.addView(Ui.button(this, "+  Další pomocníci ($hidden)", Ui.BG, Ui.ACCENT, sp = 20f) { showMore = true; render() })
        }

        val n = selected.size
        root.addView(Ui.button(this, if (n > 0) "Pokračovat ($n)" else "Vyberte alespoň jednoho pomocníka",
            if (n > 0) Ui.ACCENT else Ui.MUTED) { if (n > 0) installSelected() })
    }

    private fun moduleCard(m: Module, installed: Set<String>): View {
        val on = m.id in selected
        val card = Ui.card(this).apply {
            background = Ui.rounded(if (on) Ui.ACCENT_SOFT else Ui.CARD, 20, this@MainActivity, if (on) Ui.ACCENT else null)
        }
        card.addView(Ui.text(this, "${m.icon}  ${m.name}", 22f, bold = true))
        card.addView(Ui.text(this, m.desc, 18f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Více informací", Ui.CARD, Ui.ACCENT, sp = 16f) {
            infoId = m.id; infoFrom = Screen.CATALOG; go(Screen.INFO)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        val action = when {
            m.id in installed -> Ui.text(this, "✓ Zapnuto", 18f, bold = true, color = Ui.OK, center = true)
            !m.ready -> Ui.text(this, "Připravujeme", 17f, color = Ui.MUTED, center = true)
            else -> Ui.button(this, if (on) "✓ Vybráno" else "Instalovat", if (on) Ui.ACCENT else Ui.CARD, if (on) android.graphics.Color.WHITE else Ui.ACCENT, sp = 18f) {
                if (on) selected.remove(m.id) else selected.add(m.id); render()
            }
        }
        row.addView(action, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        card.addView(row)
        return card
    }

    private fun info(root: LinearLayout) {
        back(root)
        val m = Modules.byId(infoId)
        val installed = m.id in Modules.installed(this)
        root.addView(Ui.text(this, m.icon, 64f, center = true))
        root.addView(Ui.text(this, m.name, 28f, bold = true, center = true))
        root.addView(Ui.text(this, m.co, 22f))
        val all = listOf(m.co, "Jak to funguje.") + m.jak + listOf(m.rodina, m.bezpeci)
        root.addView(Ui.button(this, "🔊  Přečíst mi to celé", Ui.CARD, Ui.ACCENT, sp = 18f) { speaker.say(all.joinToString(" ")) })

        root.addView(section("Jak to funguje", m.jak.mapIndexed { i, s -> "${i + 1}.  $s" }.joinToString("\n\n")))
        root.addView(section("Co uvidí rodina", m.rodina))
        root.addView(section("Je to bezpečné?", m.bezpeci + (m.pozn?.let { "\n\n$it" } ?: "")))

        when {
            installed -> Unit
            !m.ready -> root.addView(Ui.button(this, "Připravujeme", Ui.MUTED) { })
            m.id in selected -> root.addView(Ui.button(this, "✓ Vybráno, zpět na nabídku") { go(Screen.CATALOG) })
            else -> root.addView(Ui.button(this, "Instalovat") { selected.add(m.id); go(Screen.CATALOG) })
        }
    }

    private fun section(title: String, body: String): View {
        val c = Ui.card(this, 8)
        c.addView(Ui.text(this, title, 21f, bold = true))
        c.addView(Ui.text(this, body, 18f))
        return c
    }

    // ---------- Instalace ----------

    private fun installSelected() {
        val fresh = selected.filter { it !in Modules.installed(this) }
        fresh.forEach { Modules.install(this, it) }
        Modules.setOnboarded(this)
        selected.clear()
        if (Modules.DN in fresh && !DnSetupActivity.allDone(this)) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, DnSetupActivity::class.java), REQ_DN_SETUP)
        }
        // Po návratu z průvodce (i přes Zpět) se ukáže „Hotovo“.
        screen = Screen.DONE
        render()
    }

    private fun done(root: LinearLayout) {
        val n = Modules.installed(this).size
        spacer(root, 40)
        root.addView(Ui.text(this, "✅", 72f, center = true))
        root.addView(Ui.text(this, "Hotovo", 36f, bold = true, center = true))
        val name = Modules.forWhom(this)
        val icons = if (n == 1) "jen jednu velkou ikonu" else "jen $n velké ikony"
        val t = if (name != null) "$name teď uvidí $icons. Nic dalšího." else "Teď uvidíte $icons. Nic dalšího."
        root.addView(Ui.text(this, t, 20f, color = Ui.MUTED, center = true))
        spacer(root, 40)
        root.addView(Ui.button(this, "Ukázat úvodní obrazovku") { go(Screen.HOME) })
    }

    // ---------- Úvodní obrazovka ----------

    private fun home(root: LinearLayout) {
        val installed = Modules.installed(this)
        root.addView(Ui.text(this, "Dobrý den", 34f, bold = true))

        if (Modules.DN in installed) {
            val ok = DnSetupActivity.allDone(this)
            val status = Ui.card(this, 4).apply {
                background = Ui.rounded(if (ok) Ui.OK_SOFT else Ui.WARN_SOFT, 18, this@MainActivity)
            }
            status.addView(Ui.text(this, if (ok) "✅  Všechno v pořádku" else "⚠️  Ochrana není celá zapnutá", 20f, bold = true,
                color = if (ok) Ui.OK else Ui.WARN))
            status.addView(Ui.text(this, if (ok) "Hovory a SMS jsou pod dohledem." else "Ťukněte sem a dokončete nastavení.", 17f))
            if (!ok) status.setOnClickListener { startActivity(Intent(this, DnSetupActivity::class.java)) }
            root.addView(status)
        }

        val tiles = mutableListOf<Pair<String, () -> Unit>>()
        for (m in Modules.all.filter { it.id in installed }) {
            if (m.id == Modules.DN) {
                tiles += "${m.icon}\n${m.name}" to { startActivity(Intent(this, DruhyNazorActivity::class.java)) }
                tiles += "✉️\nZprávy" to {
                    startActivity(Intent(this, DruhyNazorActivity::class.java).putExtra(DruhyNazorActivity.EXTRA_ONLY_MESSAGES, true))
                }
            } else {
                tiles += "${m.icon}\n${m.name}" to { infoId = m.id; infoFrom = Screen.HOME; go(Screen.INFO) }
            }
        }
        if (tiles.isEmpty()) {
            root.addView(Ui.text(this, "Zatím tu není žádný pomocník.", 19f, color = Ui.MUTED))
        }
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, open) ->
                val tile = Ui.button(this, label, Ui.CARD, Ui.INK, sp = 20f) { open() }.apply {
                    minHeight = dp(150)
                    maxLines = 4
                    ellipsize = null
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                    background = Ui.rounded(Ui.CARD, 22, this@MainActivity, Ui.LINE)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0 && pair.size == 2) marginEnd = dp(14)
                })
            }
            root.addView(row)
        }

        spacer(root, 16)
        root.addView(Ui.button(this, "+  Přidat pomocníka", Ui.CARD, Ui.ACCENT, sp = 19f) {
            selected.clear(); showMore = false; go(Screen.CATALOG)
        })
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        render()
    }

    companion object {
        private const val REQ_DN_SETUP = 20
    }
}
