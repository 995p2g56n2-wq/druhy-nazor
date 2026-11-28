package cz.druhynazor

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** Počasí: dnes a zítra jednou větou, nahlas, a co dělat, když něco hrozí. */
class WeatherActivity : Activity() {

    private lateinit var speaker: Speaker
    private var days: List<WeatherDay>? = null
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        load()
    }

    override fun onPause() { super.onPause(); speaker.stop() }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    private fun load() {
        loading = true
        render()
        Thread {
            val d = try { Weather.forecast(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                days = d; loading = false
                render()
                speak()
            }
        }.start()
    }

    private fun speak() {
        val d = days ?: run {
            speaker.say(if (Weather.place(this) == null) "Rodina ještě nevybrala, pro které místo hlásit počasí."
                        else "Počasí se teď nepodařilo zjistit. Je telefon připojený k internetu?")
            return
        }
        val today = d.firstOrNull() ?: return
        val parts = mutableListOf(Weather.sentence(today, true))
        parts += Weather.warnings(today, this).map { it.text }
        d.getOrNull(1)?.let { parts += Weather.sentence(it, false) }
        speaker.say(parts.joinToString(" "))
    }

    private fun render() {
        val root = Ui.column(this, 20, 16).apply { setBackgroundColor(Ui.BG) }
        val place = Weather.placeName(this)
        root.addView(Ui.text(this, "Počasí" + if (place.isNotEmpty()) ": $place" else "", 28f, bold = true, center = true))
        val d = days
        when {
            loading -> root.addView(Ui.text(this, "Chvilku, zjišťuji počasí…", 24f, center = true))
            d == null || d.isEmpty() -> {
                root.addView(Ui.text(this, "🌡️", 72f, center = true))
                root.addView(Ui.text(this,
                    if (Weather.place(this) == null) "Rodina ještě nevybrala, pro které místo hlásit počasí. Nastaví to v Pro rodinu, v části Počasí."
                    else "Počasí se teď nepodařilo zjistit. Je telefon připojený k internetu?", 22f, center = true))
                root.addView(Ui.button(this, "Zkusit znovu", sp = 22f) { load() })
            }
            else -> {
                val today = d[0]
                val (icon, _) = Weather.sky(today.code)
                root.addView(Ui.text(this, icon, 90f, center = true))
                root.addView(Ui.text(this, Weather.sentence(today, true), 28f, bold = true, center = true))
                for (w in Weather.warnings(today, this)) {
                    val card = Ui.card(this).apply {
                        background = Ui.rounded(if (w.level >= 2) Ui.BAD_SOFT else Ui.WARN_SOFT, 20, this@WeatherActivity)
                    }
                    card.addView(Ui.text(this, (if (w.level >= 2) "⚠️ " else "👉 ") + w.text, 24f, bold = true,
                        color = if (w.level >= 2) Ui.BAD else Ui.WARN))
                    root.addView(card)
                }
                d.getOrNull(1)?.let {
                    val card = Ui.card(this)
                    card.addView(Ui.text(this, Weather.sky(it.code).first + "  " + Weather.sentence(it, false), 22f))
                    root.addView(card)
                }
                root.addView(Ui.button(this, "🔊  Říct znovu", Ui.CARD, Ui.ACCENT, sp = 21f) { speak() })
            }
        }
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
        root.addView(Ui.text(this, "Předpověď: Open-Meteo.com (CC BY 4.0). Varování jsou pomůcka, ne oficiální výstrahy ČHMÚ.",
            13f, center = true, color = Ui.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }
}
