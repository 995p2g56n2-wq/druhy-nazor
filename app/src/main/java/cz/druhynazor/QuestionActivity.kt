package cz.druhynazor

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp
import java.util.Locale

/** Celá obrazovka po hovoru s neznámým číslem: „Chtěl po vás peníze nebo kód?“ */
class QuestionActivity : Activity() {

    private var tts: TextToSpeech? = null
    private var number = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        getSystemService(NotificationManager::class.java).cancel(Notify.ID_QUESTION)
        showQuestion()
    }

    private fun showQuestion() {
        val root = Ui.column(this, 24, 22).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER
        }
        root.addView(Ui.text(this, "Právě vám volalo neznámé číslo", 20f, color = Ui.MUTED, center = true))
        root.addView(Ui.text(this, "Chtěl po vás peníze nebo kód?", 36f, bold = true, center = true))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val yes = Ui.button(this, "ANO", Ui.BAD, sp = 36f) { answerYes() }
        val no = Ui.button(this, "NE", Ui.OK, sp = 36f) { finish() }
        val lp = { LinearLayout.LayoutParams(0, dp(150), 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
        row.addView(yes, lp()); row.addView(no, lp())
        root.addView(row)
        setContentView(root)
        say("Právě vám volalo neznámé číslo. Chtěl po vás peníze nebo kód? Klepněte ano, nebo ne.")
    }

    private fun answerYes() {
        val prefs = Prefs(this)
        Family.notify(this, "${prefs.seniorName}: po hovoru potvrdila, že chtěl peníze nebo kód",
            "Číslo: ${number.ifBlank { "skryté" }}. Zavolejte jí co nejdřív.")

        val root = Ui.column(this, 24, 22).apply {
            setBackgroundColor(Ui.BAD)
            gravity = Gravity.CENTER
        }
        root.addView(Ui.text(this, "PODVOD", 54f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, "Nic neposílejte a nikam nechoďte.", 28f, bold = true, color = Color.WHITE, center = true))
        root.addView(Ui.text(this, "Banka ani policie nikdy nechtějí kódy ani převod peněz. ${prefs.familyName} už o tom ví.",
            20f, color = Color.WHITE, center = true))
        root.addView(Ui.button(this, "📞 Zavolat: ${prefs.familyName}", Color.WHITE, Ui.INK, sp = 24f) {
            if (prefs.familyPhone.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + prefs.familyPhone)))
            }
        })
        root.addView(Ui.button(this, "Zavřít", Ui.BAD, Color.WHITE, sp = 18f) { finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BAD); isFillViewport = true; addView(root) })
        say("Podvod. Nic neposílejte a nikam nechoďte. ${prefs.familyName} už o tom ví.")
    }

    private fun say(text: String) {
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("cs", "CZ")
                tts?.setSpeechRate(0.9f)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dn")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NUMBER = "number"

        fun launch(context: Context, number: String) {
            val intent = Intent(context, QuestionActivity::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Notify.setup(context)
            val pi = Notify.pending(context, intent, Notify.ID_QUESTION)
            val n = Notification.Builder(context, Notify.CH_QUESTION)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Chtěl po vás peníze nebo kód?")
                .setContentText("Klepněte a odpovězte ANO nebo NE.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java).notify(Notify.ID_QUESTION, n)
            } catch (_: SecurityException) { }
            // Zkusíme otevřít rovnou; když to Android z pozadí nedovolí, zůstane upozornění přes celou obrazovku
            try { context.startActivity(intent) } catch (_: Exception) { }
        }
    }
}
