package cz.druhynazor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.ScrollView
import java.lang.ref.WeakReference
import java.util.Locale

/** Červená obrazovka přes celý telefon, když volá podvodné číslo. */
class ScamCallActivity : Activity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val app = intent.getStringExtra(EXTRA_APP)

        val root = Ui.column(this, 24, 24).apply {
            setBackgroundColor(Ui.BAD)
            gravity = Gravity.CENTER
        }
        root.addView(Ui.text(this, "⚠️", 72f, center = true, color = Color.WHITE))
        root.addView(Ui.text(this, "PODVODNÍK", 52f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, "NEBERTE TO!", 40f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this,
            "Volá číslo nahlášené jako podvod${if (app != null) " (přes $app)" else ""}:\n$number",
            22f, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, "${Prefs(this).familyName} už o tom ví.", 20f, color = Color.WHITE, center = true))
        root.addView(Ui.button(this, "Rozumím, nezvednu to", Color.WHITE, Ui.INK, sp = 24f) { finish() })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BAD); isFillViewport = true; addView(root) })

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("cs", "CZ")
                tts?.setSpeechRate(0.9f)
                tts?.speak("Pozor, volá podvodník. Neberte to.", TextToSpeech.QUEUE_FLUSH, null, "dn")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_APP = "app"
        private var current: WeakReference<ScamCallActivity>? = null

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        /** Spustí se chvilku po začátku zvonění, aby byla nad obrazovkou příchozího hovoru. */
        fun show(context: Context, number: String, app: String? = null) {
            val intent = Intent(context, ScamCallActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                if (app != null) putExtra(EXTRA_APP, app)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val h = Handler(Looper.getMainLooper())
            for (delay in longArrayOf(1200, 3500)) {
                h.postDelayed({
                    try { context.startActivity(intent) } catch (_: Exception) { }
                }, delay)
            }
        }

        fun close() {
            current?.get()?.finish()
            current = null
        }
    }
}
