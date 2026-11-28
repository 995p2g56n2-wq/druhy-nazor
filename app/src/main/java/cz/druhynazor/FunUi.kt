package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast

/** Karty v Pro rodinu pro pomocníky proti samotě a nudě: Od rodiny (a další přibydou). */
object FunUi {

    fun cards(a: Activity, into: android.widget.LinearLayout, onChanged: () -> Unit) {
        into.addView(Ui.text(a, "Proti samotě a nudě", 24f, bold = true))
        into.addView(familyMail(a, onChanged))
        into.addView(quiz(a, onChanged))
        into.addView(weather(a, onChanged))
        into.addView(radio(a, onChanged))
        into.addView(benefits(a, onChanged))
    }

    private fun toggle(a: Activity, id: String, label: String, onChanged: () -> Unit) = CheckBox(a).apply {
        text = label
        textSize = 18f
        setTextColor(Ui.INK)
        isChecked = Care.enabled(a, id)
        setOnCheckedChangeListener { _, on ->
            Care.setEnabled(a, id, on)
            if (id == FamilyMail.ID) { FamilyMail.schedule(a); if (on) FamilyMail.checkNow(a) }
            if (id == Weather.ID) Weather.schedule(a)
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

    // ---------- Hádanka dne ----------

    private fun quiz(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "🧩  Hádanka dne", 22f, bold = true))
        card.addView(Ui.text(a,
            "Každý den 3 otázky pro radost: přísloví, hádanky, Česko, počítání. Nikdy neřekne „špatně“, jen „Skoro, správně je…“. " +
                "Na konci si může zahrát pexeso. Funguje i bez internetu.", 17f, color = Ui.MUTED))
        card.addView(toggle(a, Quiz.ID, "Ukázat Hádanku na úvodní obrazovce", onChanged))
        if (!Quiz.enabled(a)) return card
        val last = Quiz.lastPlayed(a)
        card.addView(Ui.text(a, when {
            Quiz.doneToday(a) -> "✅ Dnes hotovo, správně ${Quiz.rightToday(a)} ze ${Quiz.PER_DAY}."
            last > 0 -> "Naposledy hrál(a): " + java.text.SimpleDateFormat("d. M. H:mm", java.util.Locale("cs")).format(java.util.Date(last))
            else -> "Zatím nehrál(a)."
        }, 17f, bold = true))
        card.addView(Ui.button(a, "Vyzkoušet Hádanku", Ui.CARD, Ui.ACCENT, sp = 16f) {
            a.startActivity(Intent(a, QuizActivity::class.java))
        })
        return card
    }

    // ---------- Počasí ----------

    private var placeDraft = ""

    private fun weather(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "☀️  Počasí a výstrahy", 22f, bold = true))
        card.addView(Ui.text(a,
            "Ráno v 8:00 se podívá na předpověď. Když hrozí vedro (od 31 °C), náledí, mráz, silný vítr nebo bouřky, " +
                "řekne nahlas jednou větou, co dělat. Dlaždice Počasí řekne předpověď na dnes a zítra kdykoli.", 17f, color = Ui.MUTED))
        card.addView(toggle(a, Weather.ID, "Hlásit počasí", onChanged))
        if (!Weather.enabled(a)) return card

        val name = Weather.placeName(a)
        card.addView(Ui.text(a, when {
            name.isNotEmpty() -> "📍 Místo: $name"
            Weather.place(a) != null -> "📍 Místo: podle polohy telefonu"
            else -> "⚠️ Napište obec, kde bydlí, ať víme, pro které místo hlásit počasí."
        }, 18f, bold = true, color = if (name.isEmpty() && Weather.place(a) == null) Ui.WARN else Ui.INK))
        val input = Ui.input(a, placeDraft, "Třeba Kladno")
        card.addView(input)
        val results = Ui.column(a, 0, 8)
        card.addView(Ui.button(a, "Najít obec", sp = 18f) {
            val q = input.text.toString().trim()
            placeDraft = q
            if (q.length < 2) { Toast.makeText(a, "Napište aspoň 2 písmena.", Toast.LENGTH_LONG).show(); return@button }
            Thread {
                val found = try { Weather.search(q) } catch (e: Exception) { emptyList() }
                a.runOnUiThread {
                    results.removeAllViews()
                    if (found.isEmpty()) results.addView(Ui.text(a, "Nic jsem nenašel. Je telefon na internetu? Zkuste jiný název.", 16f, color = Ui.WARN))
                    for (f in found) {
                        results.addView(Ui.button(a, f.name + if (f.detail.isNotEmpty()) " (${f.detail})" else "", Ui.CARD, Ui.ACCENT, sp = 16f) {
                            Weather.setPlace(a, f.name, f.lat, f.lon)
                            placeDraft = ""
                            Toast.makeText(a, "Uloženo: ${f.name}", Toast.LENGTH_LONG).show()
                            onChanged()
                        })
                    }
                }
            }.start()
        })
        card.addView(results)
        if (name.isNotEmpty() && Help.locationAllowed(a)) {
            card.addView(Ui.button(a, "Raději podle polohy telefonu", Ui.CARD, Ui.ACCENT, sp = 16f) { Weather.clearPlace(a); onChanged() })
        }
        card.addView(CheckBox(a).apply {
            text = "Při velkém vedru (od 34 °C) nebo mrznoucím dešti poslat rodině SMS (nejvýš jednu za den)"
            textSize = 17f
            setTextColor(Ui.INK)
            isChecked = Weather.familySms(a)
            setOnCheckedChangeListener { _, on -> Weather.setFamilySms(a, on) }
        })
        card.addView(Ui.button(a, "Vyzkoušet Počasí", Ui.CARD, Ui.ACCENT, sp = 16f) {
            a.startActivity(Intent(a, WeatherActivity::class.java))
        })
        card.addView(Ui.text(a, "Předpověď: Open-Meteo.com (CC BY 4.0). Pošle se jen přibližné místo (asi na 1 km). " +
            "Varování jsou pomůcka, ne oficiální výstrahy ČHMÚ.", 15f, color = Ui.MUTED))
        return card
    }

    // ---------- Rádio ----------

    private fun radio(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "📻  Rádio", 22f, bold = true))
        card.addView(Ui.text(a,
            "Dlaždice Rádio otevře aplikaci Českého rozhlasu mujRozhlas (zdarma). Tam stačí ťuknout na stanici, třeba Dvojku.",
            17f, color = Ui.MUTED))
        card.addView(toggle(a, Radio.ID, "Ukázat Rádio na úvodní obrazovce", onChanged))
        if (!Radio.enabled(a)) return card
        if (Radio.installed(a)) {
            card.addView(Ui.text(a, "✅ mujRozhlas je v telefonu.", 17f, bold = true, color = Ui.OK))
        } else {
            card.addView(Ui.text(a, "⚠️ mujRozhlas v telefonu není, dlaždice se ukáže až po instalaci. " +
                "Ťukněte na Nainstalovat a v Obchodě Play na Instalovat.", 17f, color = Ui.WARN))
            card.addView(Ui.button(a, "Nainstalovat mujRozhlas", Ui.WARN, sp = 18f) { Radio.install(a) })
        }
        return card
    }

    // ---------- Na co mám nárok ----------

    private fun benefits(a: Activity, onChanged: () -> Unit): View {
        val card = Ui.card(a)
        card.addView(Ui.text(a, "💰  Na co mám nárok", 22f, bold = true))
        card.addView(Ui.text(a,
            "Pár otázek ano / ne o bydlení a o pomoci druhého člověka. Pak řekne „možná máte nárok“ na superdávku " +
                "nebo příspěvek na péči a pošle vám SMS s odkazem na kalkulačku MPSV. Je to jen nápověda, rozhoduje Úřad práce.",
            17f, color = Ui.MUTED))
        card.addView(toggle(a, MainActivity.BENEFITS, "Ukázat na úvodní obrazovce", onChanged))
        if (!Care.enabled(a, MainActivity.BENEFITS)) return card
        card.addView(Ui.button(a, "Vyzkoušet", Ui.CARD, Ui.ACCENT, sp = 16f) {
            a.startActivity(Intent(a, BenefitsActivity::class.java))
        })
        return card
    }
}
