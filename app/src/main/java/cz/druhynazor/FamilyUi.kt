package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import cz.druhynazor.Ui.dp

/** Karta „Komu psát z rodiny“: výběr z kontaktů, ruční přidání, odebrání a zkušební SMS. */
object FamilyUi {
    const val REQ_PICK = 31

    fun card(a: Activity, onChanged: () -> Unit): View {
        val prefs = Prefs(a)
        val card = Ui.card(a)
        card.addView(Ui.text(a, "Komu z rodiny psát", 22f, bold = true))
        card.addView(Ui.text(a,
            "Když přijde podezřelá zpráva, tito lidé dostanou SMS a odpoví ANO, nebo NE. Nic nemusí instalovat.",
            17f, color = Ui.MUTED))

        val list = prefs.familyContacts
        if (list.isEmpty()) card.addView(Ui.text(a, "Zatím nikdo.", 18f, color = Ui.WARN, bold = true))
        for (c in list) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.text(a, "👤  ${c.name}\n${c.phone}", 18f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            row.addView(Ui.button(a, "Odebrat", Ui.CARD, Ui.BAD, sp = 15f) {
                prefs.familyContacts = prefs.familyContacts.filter { it != c }
                onChanged()
            }.apply { setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) })
            card.addView(row)
        }

        card.addView(Ui.button(a, "+  Vybrat z kontaktů", sp = 19f) {
            try {
                @Suppress("DEPRECATION")
                a.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK)
            } catch (e: Exception) {
                Toast.makeText(a, "Kontakty nejde otevřít, napište číslo ručně.", Toast.LENGTH_LONG).show()
            }
        })

        card.addView(Ui.text(a, "Nebo napsat ručně:", 16f, color = Ui.MUTED))
        val name = Ui.input(a, "", "Jméno, třeba Standa"); card.addView(name)
        val phone = Ui.input(a, "", "Číslo, třeba 777 123 456", phone = true); card.addView(phone)
        card.addView(Ui.button(a, "Přidat", Ui.CARD, Ui.ACCENT, sp = 17f) {
            val n = name.text.toString().trim()
            val p = phone.text.toString().trim()
            if (p.filter { it.isDigit() }.length < 9) {
                Toast.makeText(a, "Napište celé telefonní číslo.", Toast.LENGTH_LONG).show(); return@button
            }
            add(a, n.ifEmpty { p }, p)
            onChanged()
        })

        if (list.isNotEmpty()) {
            card.addView(Ui.button(a, "Poslat rodině zkušební SMS", Ui.CARD, Ui.ACCENT, sp = 17f) {
                Family.sms(a, "Postaráno: Zkouška. Takhle vám přijde zpráva, když ${prefs.seniorName} dostane něco podezřelého. Teď nemusíte odpovídat.")
                Toast.makeText(a, "Odesláno", Toast.LENGTH_LONG).show()
            })
        }
        return card
    }

    private fun add(a: Activity, name: String, phone: String) {
        val prefs = Prefs(a)
        if (prefs.familyByNumber(phone) != null) {
            Toast.makeText(a, "Tohle číslo už v seznamu je.", Toast.LENGTH_SHORT).show(); return
        }
        prefs.familyContacts = prefs.familyContacts + FamilyContact(name, phone)
    }

    /** Výsledek z výběru kontaktu. Vrací true, když šlo o něj. */
    fun onResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PICK) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        try {
            a.contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val phone = c.getString(1) ?: ""
                    val name = c.getString(0)?.trim().orEmpty().ifEmpty { phone }
                    if (phone.isNotBlank()) add(a, name, phone)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(a, "Kontakt se nepodařilo načíst, napište číslo ručně.", Toast.LENGTH_LONG).show()
        }
        return true
    }
}
