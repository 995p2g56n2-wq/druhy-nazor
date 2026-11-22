package cz.druhynazor

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Vyvede seniora z nastavení telefonu zpátky do Postaráno.
 *
 * Po povolení přístupu k oznámením telefon (aspoň Samsung) naši aplikaci na pozadí ukončí,
 * takže průvodce zmlkne. Přístup k oznámením ale hned spustí ChatWatcher a ten zavolá
 * [afterGrant]: zkusí Postaráno vrátit dopředu, a když to telefon nedovolí, nahlas navede
 * na šipku vlevo nahoře, a znovu, dokud se Postaráno neukáže.
 */
object BackGuide {
    /** Je teď vidět průvodce nebo hlavní obrazovka Postaráno? */
    @Volatile var uiVisible = false
    /** Právě navádíme na šipku (průvodce mezitím neopakuje svůj pokyn). */
    @Volatile var active = false

    private val main = Handler(Looper.getMainLooper())
    private var speaker: Speaker? = null

    /** Průvodce otevřel nastavení přepínače, který se má po zapnutí vyvést ven. */
    fun expect(ctx: Context) {
        ctx.getSharedPreferences("back_guide", Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis()).commit()
    }

    /** Zavolá ChatWatcher, když ho telefon po povolení spustil. */
    fun afterGrant(ctx: Context) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("back_guide", Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (System.currentTimeMillis() - at > 5 * 60_000) return
        p.edit().remove("at").commit()
        main.post { run(app) }
    }

    private fun run(app: Context) {
        if (uiVisible) return
        active = true
        comeBack(app)
        speaker?.shutdown()
        val sp = if (Prefs(app).voiceOk) Speaker(app) else null
        speaker = sp
        var n = 0
        val say = object : Runnable {
            override fun run() {
                if (uiVisible || n >= 6) { active = false; sp?.shutdown(); return }
                sp?.say(if (n == 0) "Teď zmáčkněte šipku vlevo nahoře." else "Zmáčkněte ještě jednou šipku vlevo nahoře.")
                n++
                main.postDelayed(this, 8000)
            }
        }
        main.postDelayed(say, 1500)
    }

    /**
     * Přenést Postaráno dopředu, jako by někdo ťukl na jeho ikonu. Od Androidu 15 to jde jen,
     * když je zrovna vidět naše překrytí, proto na okamžik přidáme neviditelný bod.
     */
    fun comeBack(app: Context) {
        val wm = app.getSystemService(WindowManager::class.java)
        var dot: View? = null
        if (Settings.canDrawOverlays(app) && wm != null) try {
            dot = View(app)
            wm.addView(dot, WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT))
        } catch (_: Exception) { dot = null }
        try {
            app.startActivity(Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
        dot?.let { v -> main.postDelayed({ try { wm?.removeView(v) } catch (_: Exception) { } }, 1500) }
    }
}
