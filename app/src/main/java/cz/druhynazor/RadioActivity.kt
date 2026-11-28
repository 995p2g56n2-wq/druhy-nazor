package cz.druhynazor

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import cz.druhynazor.Ui.dp

/**
 * Rádio: velká tlačítka stanic, jedno ťuknutí a hraje. Z úvodní dlaždice se rovnou pustí naposledy
 * poslouchaná stanice (poprvé Dvojka). Velké tlačítko Vypnout, hlasitost Tišeji / Hlasitěji.
 */
class RadioActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTOPLAY, false) && !Radio.playing) {
            Radio.play(this, Radio.last(this))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        Radio.listener = { if (!isFinishing) render(keep = true) }
        render(keep = true)
    }

    override fun onPause() {
        super.onPause()
        Radio.listener = null
    }

    private fun render(keep: Boolean = false) {
        val root = Ui.column(this, 20, 14).apply { setBackgroundColor(Ui.BG) }
        val now = Radio.current
        root.addView(Ui.text(this, "📻 Rádio", 32f, bold = true, center = true))
        val status = when (Radio.state) {
            Radio.State.PLAYING -> "▶ Hraje: ${now?.name}"
            Radio.State.LOADING -> "⏳ Ladím: ${now?.name}…"
            Radio.State.ERROR -> "Rádio teď nejde pustit. Je telefon připojený k internetu?"
            Radio.State.OFF -> "Ťukněte na stanici, kterou chcete poslouchat."
        }
        root.addView(Ui.text(this, status, 24f, bold = true, center = true,
            color = when (Radio.state) { Radio.State.PLAYING -> Ui.OK; Radio.State.ERROR -> Ui.WARN; else -> Ui.INK }))

        if (Radio.playing) {
            root.addView(Ui.button(this, "⏹  Vypnout rádio", Ui.BAD, sp = 28f) { Radio.stop(this) }.apply { minHeight = dp(110) })
            val audio = getSystemService(AudioManager::class.java)
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            val lp = { android.widget.LinearLayout.LayoutParams(0, dp(90), 1f).apply { marginEnd = dp(6); marginStart = dp(6) } }
            row.addView(Ui.button(this, "🔉 Tišeji", Ui.CARD, Ui.ACCENT, sp = 22f) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }, lp())
            row.addView(Ui.button(this, "🔊 Hlasitěji", Ui.CARD, Ui.ACCENT, sp = 22f) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }, lp())
            root.addView(row)
        }

        root.addView(Ui.text(this, "Stanice Českého rozhlasu:", 20f, color = Ui.MUTED))
        for (s in Radio.STATIONS) {
            val on = now?.id == s.id && Radio.playing
            root.addView(Ui.button(this, (if (on) "▶  " else "") + "${s.name}\n${s.about}",
                if (on) Ui.OK else Ui.ACCENT, sp = 24f) {
                if (!on) Radio.play(this, s)
            }.apply { minHeight = dp(100) })
        }
        root.addView(Ui.text(this, "Rádio hraje i se zhasnutou obrazovkou. Vypnete ho tady nebo ťuknutím na oznámení nahoře. " +
            "Spotřebuje asi 60 MB dat za hodinu, na Wi-Fi je zdarma.", 16f, center = true, color = Ui.MUTED))
        root.addView(Ui.text(this, "Vysílání: Český rozhlas", 15f, center = true, color = Ui.MUTED))
        root.addView(Ui.button(this, "Zpět", Ui.BG, Ui.MUTED, sp = 20f) { finish() })
        Ui.show(this, root, keep = keep)
    }

    companion object {
        const val EXTRA_AUTOPLAY = "hned"
    }
}
