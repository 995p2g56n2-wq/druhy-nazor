package cz.druhynazor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Český hlas. Samsung vlastní hlas česky neumí, proto aplikace mluví vždy hlasem Google,
 * když je v telefonu. Nemusí se nic přepínat v nastavení telefonu.
 */
object Voice {
    const val GOOGLE = "com.google.android.tts"
    val CS = Locale("cs", "CZ")

    fun googleInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(GOOGLE, 0).enabled
    } catch (e: Exception) { false }

    /** Hlas pro celou aplikaci: Google, když je v telefonu, jinak výchozí hlas telefonu. */
    fun tts(ctx: Context, listener: TextToSpeech.OnInitListener): TextToSpeech =
        TextToSpeech(ctx, listener, if (googleInstalled(ctx)) GOOGLE else null)

    /** Umí telefon česky? Výsledek přijde na hlavním vlákně. */
    fun check(ctx: Context, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!googleInstalled(ctx)) { main.post { done(false) }; return }
        var answered = false
        fun answer(ok: Boolean) { main.post { if (!answered) { answered = true; done(ok) } } }
        lateinit var t: TextToSpeech
        t = TextToSpeech(ctx.applicationContext, { status ->
            val ok = try {
                status == TextToSpeech.SUCCESS && t.isLanguageAvailable(CS) >= TextToSpeech.LANG_AVAILABLE
            } catch (e: Exception) { false }
            Log.i("DruhyNazor", "Český hlas: $ok")
            try { t.shutdown() } catch (_: Exception) { }
            answer(ok)
        }, GOOGLE)
        // Kdyby hlas vůbec neodpověděl, nečekat věčně
        main.postDelayed({ answer(false) }, 6000)
    }

    /** Otevře správné místo: Obchod Play s hlasem Google, nebo stažení češtiny. */
    fun fix(a: Activity) {
        if (!googleInstalled(a)) { openPlay(a); return }
        try {
            a.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(GOOGLE))
        } catch (e: Exception) { openPlay(a) }
    }

    private fun openPlay(a: Activity) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE")))
        } catch (e: Exception) {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE")))
        }
    }
}
