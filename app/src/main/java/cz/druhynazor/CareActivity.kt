package cz.druhynazor

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp
import java.time.LocalTime

/**
 * Celá obrazovka pro seniora, sama vyskočí a mluví:
 * - Léky: „Je čas na lék.“ a jedno velké tlačítko Vzala jsem.
 * - Jsem v pořádku: „Jak se máte?“ a dvě tlačítka Dobře / Není mi dobře.
 */
class CareActivity : Activity() {

    private lateinit var speaker: Speaker
    private var mode = MODE_MEDS
    private var test = false
    private var testMed: String? = null
    private val repeat = Runnable { lastSaid?.let { if (!isFinishing) speaker.say(it) } }
    private var lastSaid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speaker = Speaker(this)
        read(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent) {
        mode = i.getStringExtra(EXTRA_MODE) ?: MODE_MEDS
        test = i.getBooleanExtra(EXTRA_TEST, false)
        testMed = i.getStringExtra(EXTRA_TEST_MED)
        getSystemService(NotificationManager::class.java)?.cancel(if (mode == MODE_MEDS) NOTIF_MEDS else NOTIF_CHECK)
        if (mode == MODE_MEDS) meds() else check()
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(repeat)
        speaker.stop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    /** Říct nahlas a po 30 vteřinách ještě jednou, kdyby to senior napoprvé neslyšel. */
    private fun say(text: String, again: Boolean = true) {
        lastSaid = text
        speaker.say(text)
        window.decorView.removeCallbacks(repeat)
        if (again) window.decorView.postDelayed(repeat, 30_000)
    }

    private fun show(root: LinearLayout, bg: Int) {
        root.setBackgroundColor(bg)
        root.gravity = Gravity.CENTER_HORIZONTAL
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(root) })
    }

    private fun spacer(root: LinearLayout, h: Int) = root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(h)))

    // ---------- Léky ----------

    private fun meds() {
        val prefs = Prefs(this)
        val due = if (test) listOf(Med("zkouska", testMed ?: "Zkušební lék", 8, 0)) else Care.dueMeds(this)
        if (due.isEmpty()) { finish(); return }
        val names = due.joinToString(", ") { it.name }
        val label = Care.takenLabel(prefs)
        val root = Ui.column(this, 24, 22)
        spacer(root, 24)
        root.addView(Ui.text(this, "💊", 90f, center = true))
        root.addView(Ui.text(this, "Je čas na lék", 34f, bold = true, center = true))
        root.addView(Ui.text(this, names, 32f, bold = true, center = true, color = Ui.ACCENT))
        root.addView(Ui.text(this, "Až si ho vezmete, ťukněte dole na $label.", 22f, center = true))
        spacer(root, 16)
        root.addView(Ui.text(this, "👇", 44f, center = true))
        root.addView(big("✅  $label", Ui.OK) {
            if (!test) Care.markTaken(this, due)
            thanks("Děkuji, zapsáno.", Ui.OK_SOFT)
        })
        show(root, Ui.BG)
        say("Je čas na lék. $names. Až si ho vezmete, ťukněte dole na $label.")
    }

    // ---------- Jsem v pořádku ----------

    private fun check() {
        if (!test && !Care.checkPending(this)) { finish(); return }
        val hello = if (LocalTime.now().hour < 11) "Dobré ráno" else "Dobrý den"
        val root = Ui.column(this, 24, 22)
        spacer(root, 24)
        root.addView(Ui.text(this, "❤️", 90f, center = true))
        root.addView(Ui.text(this, "$hello,\njak se máte?", 36f, bold = true, center = true))
        root.addView(Ui.text(this, "Ťukněte dole na Dobře, nebo na Není mi dobře.", 22f, center = true))
        spacer(root, 16)
        root.addView(big("👍  Dobře", Ui.OK) {
            if (!test) Care.answerCheck(this, true)
            thanks("Výborně. Hezký den.", Ui.OK_SOFT)
        })
        root.addView(big("😟  Není mi dobře", Ui.WARN) {
            if (!test) Care.answerCheck(this, false)
            notWell()
        })
        show(root, Ui.BG)
        say("$hello, jak se máte? Ťukněte dole na Dobře, nebo na Není mi dobře.")
    }

    private fun notWell() {
        val prefs = Prefs(this)
        val root = Ui.column(this, 24, 22)
        spacer(root, 24)
        root.addView(Ui.text(this, "📞", 80f, center = true))
        val who = prefs.familyFirstName
        val line = if (prefs.familyContacts.isEmpty()) "Zavolejte někomu blízkému." else "$who už o tom ví a zavolá vám."
        root.addView(Ui.text(this, line, 30f, bold = true, center = true))
        root.addView(Ui.text(this, "Nebo ťukněte dole na Zavolat.", 22f, center = true))
        if (prefs.familyPhone.isNotBlank()) {
            root.addView(big("📞  Zavolat: $who", Ui.ACCENT) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Family.cleanNumber(prefs.familyPhone))))
            })
        }
        root.addView(Ui.button(this, "Zavřít", Ui.CARD, Ui.MUTED, sp = 20f) { finish() })
        show(root, Ui.WARN_SOFT)
        say("$line Nebo ťukněte dole na Zavolat.", again = false)
    }

    private fun thanks(text: String, bg: Int) {
        val root = Ui.column(this, 24, 22)
        spacer(root, 120)
        root.addView(Ui.text(this, "✅", 90f, center = true))
        root.addView(Ui.text(this, text, 34f, bold = true, center = true, color = Ui.OK))
        show(root, bg)
        say(text, again = false)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 4000)
    }

    private fun big(label: String, bg: Int, onClick: () -> Unit) =
        Ui.button(this, label, bg, Color.WHITE, sp = 30f) { onClick() }.apply {
            minHeight = dp(130)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    companion object {
        const val EXTRA_MODE = "rezim"
        const val EXTRA_TEST = "zkouska"
        const val EXTRA_TEST_MED = "zkusebni_lek"
        const val MODE_MEDS = "leky"
        const val MODE_CHECK = "jak_se_mate"
        private const val NOTIF_MEDS = 8100
        private const val NOTIF_CHECK = 8101

        fun showMeds(ctx: Context) = open(ctx, MODE_MEDS, NOTIF_MEDS, "💊 Je čas na lék",
            Care.dueMeds(ctx).joinToString(", ") { it.name })

        fun showCheck(ctx: Context) = open(ctx, MODE_CHECK, NOTIF_CHECK, "❤️ Jak se máte?", "Ťukněte sem a odpovězte.")

        private fun open(ctx: Context, mode: String, notif: Int, title: String, text: String) {
            val i = Intent(ctx, CareActivity::class.java).putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Pro jistotu i oznámení, kdyby telefon nedovolil otevřít obrazovku sám
            Notify.show(ctx, notif, Notify.CH_QUESTION, title, text, i)
            try { ctx.startActivity(i) } catch (e: Exception) { Log.w("DruhyNazor", "Obrazovku $mode se nepodařilo otevřít", e) }
        }
    }
}
