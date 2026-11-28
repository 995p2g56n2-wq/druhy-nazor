package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import cz.druhynazor.Ui.dp

/** Karty v Pro rodinu: Léky, Jsem v pořádku a Pomoc. Každý pomocník jde vypnout. */
object CareUi {
    const val REQ_HELP_PERMS = 41

    fun cards(a: Activity, into: LinearLayout, onChanged: () -> Unit) {
        into.addView(Ui.text(a, "Pomocníci na zkoušku", 24f, bold = true))
        into.addView(help(a, onChanged))
        into.addView(check(a, onChanged))
        into.addView(meds(a, onChanged))
    }

    private fun toggle(a: Activity, id: String, label: String, onChanged: () -> Unit) = CheckBox(a).apply {
        text = label
        textSize = 18f
        setTextColor(Ui.INK)
        isChecked = Care.enabled(a, id)
        setOnCheckedChangeListener { _, on -> Care.setEnabled(a, id, on); onChanged() }
    }

    // ---------- Pomoc ----------

    private fun help(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "🆘  Pomoc", 22f, bold = true))
        card.addView(Ui.text(a,
            "Velké červené tlačítko na úvodní obrazovce. Po 15 vteřinách pošle rodině SMS s polohou a zavolá prvnímu v seznamu. " +
                "Do té doby jde zrušit obrovským tlačítkem Zrušit.", 17f, color = Ui.MUTED))
        card.addView(toggle(a, Care.POMOC, "Ukázat tlačítko Pomoc", onChanged))
        if (!Care.enabled(a, Care.POMOC)) return card
        val loc = Help.locationAllowed(a)
        val call = Help.callAllowed(a)
        if (loc && call) {
            card.addView(Ui.text(a, "✅ Poloha i volání jsou povolené.", 18f, bold = true, color = Ui.OK))
        } else {
            card.addView(Ui.text(a,
                (if (!loc) "⚠️ Poloha není povolená, rodina by nevěděla, kde je. " else "") +
                    "Ťukněte na Povolit polohu a volání. V prvním okénku ťukněte na Při používání aplikace, " +
                    "v dalším na Povolit.",
                17f, color = Ui.WARN))
            card.addView(Ui.button(a, "Povolit polohu a volání", Ui.WARN, sp = 18f) {
                a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CALL_PHONE), REQ_HELP_PERMS)
            })
        }
        if (PomocWidget.placed(a)) {
            card.addView(Ui.text(a, "✅ Velké tlačítko Pomoc je na ploše.", 18f, bold = true, color = Ui.OK))
        } else {
            card.addView(Ui.text(a,
                "Velké tlačítko Pomoc jde dát přímo na plochu telefonu, aby se nemuselo nic hledat. " +
                    "Ťukněte sem a v okénku na Přidat.", 17f, color = Ui.MUTED))
            card.addView(Ui.button(a, "🆘  Dát Pomoc na plochu", Ui.BAD, sp = 19f) {
                if (!DnSetupActivity.pinShortcut(a, "pomoc"))
                    Toast.makeText(a, "Tento telefon neumí přidat tlačítko na plochu.", Toast.LENGTH_LONG).show()
            })
        }
        card.addView(Ui.button(a, "Vyzkoušet Pomoc (rodině přijde SMS se slovem ZKOUŠKA)", Ui.CARD, Ui.ACCENT, sp = 16f) {
            a.startActivity(Intent(a, HelpActivity::class.java).putExtra(HelpActivity.EXTRA_TEST, true))
        })
        return card
    }

    // ---------- Jsem v pořádku ----------

    private fun check(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "❤️  Jsem v pořádku", 22f, bold = true))
        card.addView(Ui.text(a,
            "Každý den v ${Care.checkTime(a)} se zeptá „Jak se máte?“. Když neodpoví, zeptá se znovu, " +
                "a když neodpoví do ${Care.deadlineTime(a)}, přijde vám SMS. Při odpovědi Není mi dobře vám přijde SMS hned.",
            17f, color = Ui.MUTED))
        card.addView(toggle(a, Care.OK, "Ptát se každý den", onChanged))
        if (!Care.enabled(a, Care.OK)) return card
        card.addView(Ui.button(a, "🕘  Změnit čas (teď ${Care.checkTime(a)})", Ui.CARD, Ui.ACCENT, sp = 17f) {
            TimePickerDialog(a, { _, h, m -> Care.setCheckTime(a, h, m); onChanged() },
                Care.checkHour(a), Care.checkMinute(a), true).show()
        })
        card.addView(Ui.button(a, "Vyzkoušet otázku (nic se nepošle)", Ui.CARD, Ui.ACCENT, sp = 16f) {
            a.startActivity(Intent(a, CareActivity::class.java)
                .putExtra(CareActivity.EXTRA_MODE, CareActivity.MODE_CHECK).putExtra(CareActivity.EXTRA_TEST, true))
        })
        return card
    }

    // ---------- Léky ----------

    private var draftName = ""

    private fun meds(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "💊  Léky", 22f, bold = true))
        card.addView(Ui.text(a,
            "V zadaný čas lék připomene nahlas přes celou obrazovku. Když neťukne na ${Care.takenLabel(Prefs(a))}, " +
                "připomene to znovu po 15 a 30 minutách a po hodině vám přijde SMS.", 17f, color = Ui.MUTED))
        card.addView(toggle(a, Care.LEKY, "Připomínat léky", onChanged))
        if (!Care.enabled(a, Care.LEKY)) return card

        val list = Care.meds(a)
        if (list.isEmpty()) card.addView(Ui.text(a, "Zatím žádný lék.", 18f, bold = true, color = Ui.MUTED))
        for (m in list) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.text(a, "${m.time}   ${m.name}", 19f, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            row.addView(Ui.button(a, "Odebrat", Ui.CARD, Ui.BAD, sp = 15f) {
                Care.removeMed(a, m.id)
                Toast.makeText(a, "Odebráno: ${m.name} v ${m.time}", Toast.LENGTH_SHORT).show()
                onChanged()
            }.apply { setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) })
            card.addView(row)
        }

        card.addView(Ui.text(a, "Přidat lék: napište, jak mu říká (třeba Prášek na tlak), a vyberte čas.", 16f, color = Ui.MUTED))
        val name = Ui.input(a, draftName, "Třeba Prášek na tlak")
        card.addView(name)
        card.addView(Ui.button(a, "+  Vybrat čas a přidat", sp = 19f) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { Toast.makeText(a, "Nejdřív napište, jaký lék.", Toast.LENGTH_LONG).show(); return@button }
            draftName = n
            TimePickerDialog(a, { _, h, m ->
                Care.addMed(a, n, h, m)
                draftName = ""
                Toast.makeText(a, "Přidáno: $n v " + "%d:%02d".format(h, m), Toast.LENGTH_LONG).show()
                onChanged()
            }, 8, 0, true).show()
        })
        if (list.isNotEmpty()) {
            card.addView(Ui.button(a, "Vyzkoušet připomínku (nic se nepošle)", Ui.CARD, Ui.ACCENT, sp = 16f) {
                a.startActivity(Intent(a, CareActivity::class.java)
                    .putExtra(CareActivity.EXTRA_MODE, CareActivity.MODE_MEDS)
                    .putExtra(CareActivity.EXTRA_TEST, true)
                    .putExtra(CareActivity.EXTRA_TEST_MED, list.first().name))
            })
        }
        card.addView(Ui.text(a, "Pozor: připomínka nenahrazuje lékaře. Dávkování vždy podle něj.", 15f, color = Ui.MUTED))
        return card
    }
}
