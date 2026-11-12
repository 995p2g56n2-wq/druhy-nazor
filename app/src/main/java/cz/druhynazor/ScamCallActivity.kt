package cz.druhynazor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
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
private data class Texts(val big: String, val sub: String, val info: String, val speech: String)

class ScamCallActivity : Activity() {

    private var tts: TextToSpeech? = null
    private var savedAlarmVolume = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val app = intent.getStringExtra(EXTRA_APP)
        val reason = intent.getStringExtra(EXTRA_OUTGOING)
        val family = Prefs(this).familyName
        val (big, sub, info, speech) = when (reason) {
            REASON_SCAM -> Texts("STOP!", "ZAVĚSTE!",
                "Voláte číslo nahlášené jako podvod:\n$number", "Stop. Voláte podvodníkovi. Zavěste.")
            REASON_FOREIGN -> Texts("STOP!", "ZAVĚSTE!",
                "Voláte zpátky do zahraničí na číslo, které vás prozvonilo:\n$number\nTakový hovor může být velmi drahý.",
                "Stop. Voláte zpátky na drahé zahraniční číslo. Zavěste.")
            REASON_MISSED -> Texts("PODVODNÍK", "NEVOLEJTE ZPÁTKY!",
                "Zmeškaný hovor z čísla:\n$number\nje od podvodníka.", "Volal vám podvodník. Nevolejte mu zpátky.")
            else -> Texts("PODVODNÍK", "NEBERTE TO!",
                "Volá číslo nahlášené jako podvod${if (app != null) " (přes $app)" else ""}:\n$number",
                "Pozor, volá podvodník. Neberte to.")
        }

        val root = Ui.column(this, 24, 24).apply {
            setBackgroundColor(Ui.BAD)
            gravity = Gravity.CENTER
        }
        root.addView(Ui.text(this, "⚠️", 72f, center = true, color = Color.WHITE))
        root.addView(Ui.text(this, big, 52f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, sub, 40f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, info, 22f, color = Color.WHITE, center = true))
        if (reason != REASON_MISSED) root.addView(Ui.text(this, "$family už o tom ví.", 20f, color = Color.WHITE, center = true))
        root.addView(Ui.button(this, "Rozumím", Color.WHITE, Ui.INK, sp = 24f) { finish() })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BAD); isFillViewport = true; addView(root) })

        // Hlasitost budíku na maximum (po zavření se vrátí zpět), aby hlas byl slyšet
        val audio = getSystemService(AudioManager::class.java)
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { savedAlarmVolume = -1 }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("cs", "CZ")
                tts?.setSpeechRate(0.9f)
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
                tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, params, "dn1")
                tts?.playSilentUtterance(700, TextToSpeech.QUEUE_ADD, "pauza")
                tts?.speak(speech, TextToSpeech.QUEUE_ADD, params, "dn2")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (savedAlarmVolume >= 0) {
            try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) } catch (_: Exception) { }
        }
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_APP = "app"
        const val EXTRA_OUTGOING = "reason"
        const val REASON_SCAM = "scam"
        const val REASON_FOREIGN = "foreign"
        const val REASON_MISSED = "missed"
        private var current: WeakReference<ScamCallActivity>? = null

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        /** Spustí se chvilku po začátku zvonění, aby byla nad obrazovkou příchozího hovoru. */
        fun show(context: Context, number: String, app: String? = null, outgoing: String? = null) {
            val intent = Intent(context, ScamCallActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                if (app != null) putExtra(EXTRA_APP, app)
                if (outgoing != null) putExtra(EXTRA_OUTGOING, outgoing)
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
