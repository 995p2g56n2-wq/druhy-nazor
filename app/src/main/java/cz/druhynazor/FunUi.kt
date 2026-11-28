package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.Toast

/** Karty v Pro rodinu pro pomocníky proti samotě a nudě: Od rodiny (a další přibydou). */
object FunUi {

    fun cards(a: Activity, into: android.widget.LinearLayout, onChanged: () -> Unit) {
        into.addView(Ui.text(a, "Proti samotě a nudě", 24f, bold = true))
        into.addView(familyMail(a, onChanged))
    }

    private fun toggle(a: Activity, id: String, label: String, onChanged: () -> Unit) = CheckBox(a).apply {
        text = label
        textSize = 18f
        setTextColor(Ui.INK)
        isChecked = Care.enabled(a, id)
        setOnCheckedChangeListener { _, on ->
            Care.setEnabled(a, id, on)
            if (id == FamilyMail.ID) { FamilyMail.schedule(a); if (on) FamilyMail.checkNow(a) }
            onChanged()
        }
    }

    // ---------- Od rodiny ----------

    private fun familyMail(a: Activity, onChanged: () -> Unit): View {
        val prefs = Prefs(a)
        val card = Ui.card(a)
        card.addView(Ui.text(a, "📷  Od rodiny", 22f, bold = true))
        card.addView(Ui.text(a,
            "Pošlete ${prefs.seniorGenitive.let { if (it == "vašeho blízkého") "svému blízkému" else "na telefon $it" }} fotku, " +
                "hlasový nebo psaný vzkaz. Ukáže se přes celou obrazovku a přečte se nahlas. Odpovědět může srdíčkem nebo vám zavolá. " +
                "Každý z rodiny má vlastní odkaz, přijde mu jednou v SMS. Telefon se dívá na novinky každých 15 minut, potřebuje internet.",
            17f, color = Ui.MUTED))
        card.addView(toggle(a, FamilyMail.ID, "Zapnout Od rodiny", onChanged))
        if (!FamilyMail.enabled(a)) return card
        if (prefs.familyContacts.isEmpty()) {
            card.addView(Ui.text(a, "Nejdřív přidejte někoho z rodiny (výše).", 17f, color = Ui.WARN))
            return card
        }
        val ok = FamilyMail.lastOk(a)
        card.addView(Ui.text(a,
            if (ok > 0) "✅ Spojení funguje." else "⏳ Ještě se nepodařilo spojit se serverem. Je telefon připojený k internetu?",
            17f, bold = true, color = if (ok > 0) Ui.OK else Ui.WARN))
        for (c in prefs.familyContacts) {
            card.addView(Ui.text(a, "${c.name}: " + if (FamilyMail.linkSent(a, c)) "odkaz poslán v SMS ✓" else "odkaz se pošle, až bude spojení",
                17f, bold = true))
            card.addView(Ui.button(a, "Poslat odkaz znovu v SMS: ${c.name}", Ui.CARD, Ui.ACCENT, sp = 16f) {
                Toast.makeText(a, "Posílám…", Toast.LENGTH_SHORT).show()
                FamilyMail.resend(a, c) { sent ->
                    Toast.makeText(a, if (sent) "Odkaz odešel: ${c.name}" else "Nepodařilo se. Má telefon internet a kredit?", Toast.LENGTH_LONG).show()
                    onChanged()
                }
            })
            card.addView(Ui.button(a, "Poslat odkaz jinak (WhatsApp, e-mail): ${c.name}", Ui.CARD, Ui.ACCENT, sp = 16f) {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Fotky a vzkazy na telefon ${prefs.seniorGenitive} posílejte sem: ${FamilyMail.link(a, c)}")
                a.startActivity(Intent.createChooser(i, "Poslat odkaz"))
            })
        }
        card.addView(Ui.button(a, "Podívat se teď na novinky", Ui.CARD, Ui.ACCENT, sp = 16f) {
            FamilyMail.checkNow(a) { n ->
                Toast.makeText(a, when {
                    n < 0 -> "Spojení se nepodařilo. Je telefon připojený k internetu?"
                    n == 0 -> "Nic nového."
                    else -> "Nové věci: $n"
                }, Toast.LENGTH_LONG).show()
                if (n > 0) FamilyMail.announce(a)
                onChanged()
            }
        })
        card.addView(Ui.text(a, "Fotky a vzkazy se samy smažou po 30 dnech. Odkaz nikomu cizímu neposílejte.", 15f, color = Ui.MUTED))
        return card
    }
}
