package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import cz.druhynazor.Ui.dp

/**
 * Na co mám nárok: pár otázek ano / ne a pak „možná máte nárok“, nikdy „máte nárok“.
 * Rozhoduje vždy Úřad práce. Rodina dostane SMS, ať to se seniorem zkusí.
 *
 * Ověřeno 28. 11. 2026:
 * - Superdávka (dávka státní sociální pomoci) od 1. 10. 2025 nahradila mimo jiné příspěvek na bydlení (ČT24, 1. 10. 2025).
 *   Orientační kalkulačka MPSV: mpsv.gov.cz/dssp-kalkulacka. Žádá se přes Jenda nebo na Úřadu práce.
 * - Příspěvek na péči: pro lidi, kteří kvůli zdraví potřebují pomoc s 10 základními potřebami (mobilita, oblékání, hygiena,
 *   stravování, péče o domácnost…). Žádost na Úřadu práce. Bezplatná linka ÚP ČR 800 779 900 (mpsv.gov.cz, 6. 8. 2026).
 */
class BenefitsActivity : Activity() {

    private data class Step(val id: String, val question: String, val skipIf: String? = null)

    private val steps = listOf(
        Step("bydleni", "Platíte za bydlení, tedy nájem, energie a vodu, víc než třetinu svého důchodu?"),
        Step("bydleni_uz", "Dostáváte už od státu peníze na bydlení?", skipIf = "bydleni"),
        Step("pece", "Potřebujete kvůli zdraví pomoc jiného člověka? Třeba s mytím, oblékáním, vařením, úklidem nebo nákupy."),
        Step("pece_uz", "Dostáváte už příspěvek na péči?", skipIf = "pece"),
    )

    private val answers = mutableMapOf<String, Boolean>()
    private var index = 0
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        render()
    }

    override fun onPause() { super.onPause(); speaker.stop() }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    private fun current(): Step? {
        while (index < steps.size) {
            val s = steps[index]
            // Doplňující otázka jen tehdy, když na tu předchozí řekl Ano
            if (s.skipIf != null && answers[s.skipIf] != true) { index++; continue }
            return s
        }
        return null
    }

    private fun housing() = answers["bydleni"] == true && answers["bydleni_uz"] != true
    private fun care() = answers["pece"] == true && answers["pece_uz"] != true

    private fun render() {
        val root = Ui.column(this, 20, 16).apply { setBackgroundColor(Ui.BG) }
        val step = current()
        if (step == null) result(root) else question(root, step)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun question(root: LinearLayout, s: Step) {
        root.addView(Ui.text(this, "💰 Na co mám nárok", 26f, bold = true, center = true))
        if (index == 0) root.addView(Ui.text(this, "Zeptám se na pár věcí. Stačí ťuknout Ano nebo Ne.", 20f, center = true, color = Ui.MUTED))
        root.addView(Ui.text(this, s.question, 28f, bold = true, center = true))
        root.addView(Ui.button(this, "Ano", Ui.OK, sp = 28f) { answers[s.id] = true; index++; render() }.apply { minHeight = dp(100) })
        root.addView(Ui.button(this, "Ne", Ui.CARD, Ui.INK, sp = 28f) { answers[s.id] = false; index++; render() }.apply { minHeight = dp(100) })
        root.addView(Ui.button(this, "Nevím", Ui.BG, Ui.MUTED, sp = 20f) { answers[s.id] = true; index++; render() })
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
        speaker.say((if (index == 0) "Zeptám se na pár věcí. " else "") + s.question)
    }

    private fun result(root: LinearLayout) {
        val prefs = Prefs(this)
        root.addView(Ui.text(this, "💰 Na co mám nárok", 26f, bold = true, center = true))
        val found = mutableListOf<String>()
        if (housing()) {
            found += "Možná vám stát přispěje na bydlení. Říká se tomu superdávka."
        }
        if (care()) {
            found += "Možná máte nárok na příspěvek na péči. Jsou to peníze na to, aby vám někdo pomáhal."
        }
        if (found.isEmpty()) {
            val line = "Podle odpovědí teď nic nového nevychází. Když se něco změní, zkuste to znovu."
            root.addView(Ui.text(this, "👍", 72f, center = true))
            root.addView(Ui.text(this, line, 24f, center = true))
            speaker.say(line)
        } else {
            for (f in found) {
                val card = Ui.card(this).apply { background = Ui.rounded(Ui.OK_SOFT, 20, this@BenefitsActivity) }
                card.addView(Ui.text(this, "👉 $f", 24f, bold = true))
                root.addView(card)
            }
            val ask = if (prefs.familyContacts.isNotEmpty()) "Pošlu to rodině, ať to s vámi zkusí. Ťukněte na Poslat rodině." else "Zavolejte na Úřad práce, je to zdarma."
            root.addView(Ui.text(this, ask, 22f, center = true))
            speaker.say(found.joinToString(" ") + " " + ask)
            if (prefs.familyContacts.isNotEmpty() && canSms()) {
                root.addView(Ui.button(this, "📨  Poslat rodině", Ui.OK, sp = 26f) { sendFamily() }.apply { minHeight = dp(96) })
            }
            root.addView(Ui.button(this, "📞  Úřad práce, zdarma: 800 779 900", Ui.CARD, Ui.ACCENT, sp = 20f) {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:800779900"))) } catch (_: Exception) { }
            })
        }
        root.addView(Ui.text(this, "Je to jen nápověda. Jestli nárok opravdu máte, rozhoduje vždy Úřad práce.", 17f, center = true, color = Ui.MUTED))
        root.addView(Ui.button(this, "Začít znovu", Ui.CARD, Ui.ACCENT, sp = 18f) { answers.clear(); index = 0; render() })
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
    }

    private fun canSms() = checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun sendFamily() {
        val who = Prefs(this).seniorName
        val parts = mutableListOf<String>()
        if (housing()) parts += "mozna ma narok na pomoc s bydlenim (superdavka), spocitejte: mpsv.gov.cz/dssp-kalkulacka"
        if (care()) parts += "mozna ma narok na prispevek na peci, Urad prace 800 779 900"
        val text = "Postarano: $who " + parts.joinToString(", a ") + ". Zkuste to spolu."
        Family.sms(this, text, maxParts = 2) { ok ->
            Toast.makeText(this, if (ok) "Odesláno rodině." else "SMS neodešla. Je na kartě kredit?", Toast.LENGTH_LONG).show()
            if (ok) speaker.say("Odesláno rodině.")
        }
    }
}
