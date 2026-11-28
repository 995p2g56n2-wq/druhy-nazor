package cz.druhynazor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp

/**
 * Hádanka dne: 3 otázky denně, velká tlačítka, vše nahlas. Na konci Pexeso.
 * Nikdy „špatně“, jen „Skoro! Správně je…“.
 */
class QuizActivity : Activity() {

    private lateinit var speaker: Speaker
    private var question: QuizQuestion? = null
    private var shuffled: List<String> = emptyList()
    private var picked: String? = null
    private var pexeso = false

    // Pexeso
    private val symbols = listOf("🍎", "🌻", "🐱", "🚂", "☕", "🎈")
    private var cards: List<String> = emptyList()
    private val open = mutableSetOf<Int>()
    private val found = mutableSetOf<Int>()
    private var tries = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        if (Quiz.doneToday(this)) render() else newQuestion()
    }

    override fun onPause() { super.onPause(); speaker.stop() }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (pexeso) { pexeso = false; render() } else { @Suppress("DEPRECATION") super.onBackPressed() }
    }

    private fun newQuestion() {
        val q = Quiz.next(this)
        question = q
        shuffled = q.answers.shuffled()
        picked = null
        render()
        val n = Quiz.answeredToday(this) + 1
        speaker.say((if (n == 1) "Hádanka dne. " else "") + q.text + " " + shuffled.joinToString(", nebo ") + "?")
    }

    private fun render() {
        val root = Ui.column(this, 20, 16).apply { setBackgroundColor(Ui.BG) }
        when {
            pexeso -> pexesoScreen(root)
            question != null -> questionScreen(root, question!!)
            else -> doneScreen(root)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun questionScreen(root: LinearLayout, q: QuizQuestion) {
        val answered = Quiz.answeredToday(this)
        val number = if (picked == null) answered + 1 else answered
        root.addView(Ui.text(this, "🧩 Hádanka dne", 26f, bold = true, center = true))
        if (number <= Quiz.PER_DAY) root.addView(Ui.text(this, "Otázka $number ze ${Quiz.PER_DAY}", 19f, center = true, color = Ui.MUTED))
        root.addView(Ui.text(this, q.text, 30f, bold = true, center = true))
        for (a in shuffled) {
            val bg = when {
                picked == null -> Ui.CARD
                a == q.correct -> Ui.OK
                a == picked -> Ui.WARN_SOFT
                else -> Ui.CARD
            }
            val fg = if (picked != null && a == q.correct) Color.WHITE else Ui.INK
            root.addView(Ui.button(this, a, bg, fg, sp = 26f) {
                if (picked != null) return@button
                picked = a
                val right = a == q.correct
                Quiz.record(this, right)
                render()
                speaker.say(if (right) "Správně! Výborně." else "Skoro! Správně je: ${q.correct}.")
            }.apply { minHeight = dp(96) })
        }
        if (picked != null) {
            val right = picked == q.correct
            root.addView(Ui.text(this, if (right) "✅ Správně! Výborně." else "Skoro! Správně je: ${q.correct}.", 26f, bold = true,
                center = true, color = if (right) Ui.OK else Ui.WARN))
            if (!Quiz.doneToday(this)) {
                root.addView(Ui.button(this, "Další otázka ▶", sp = 26f) { newQuestion() }.apply { minHeight = dp(100) })
            } else {
                root.addView(Ui.button(this, "Hotovo ▶", Ui.OK, sp = 26f) { question = null; render(); sayDone() }.apply { minHeight = dp(100) })
            }
        }
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
    }

    private fun sayDone() {
        speaker.say("Hotovo na dnešek. Správně ${Quiz.rightToday(this)} ze ${Quiz.PER_DAY}. Zítra budou nové otázky. Můžete si ještě zahrát pexeso.")
    }

    private fun doneScreen(root: LinearLayout) {
        root.addView(Ui.text(this, "🧩", 80f, center = true))
        root.addView(Ui.text(this, "Hotovo na dnešek", 32f, bold = true, center = true))
        root.addView(Ui.text(this, "Správně ${Quiz.rightToday(this)} ze ${Quiz.PER_DAY}. Zítra budou nové otázky.", 24f, center = true))
        root.addView(Ui.button(this, "🃏  Zahrát si pexeso", sp = 26f) { startPexeso() }.apply { minHeight = dp(100) })
        root.addView(Ui.button(this, "Ještě jedna otázka navíc", Ui.CARD, Ui.ACCENT, sp = 21f) { newQuestion() })
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
    }

    // ---------- Pexeso: 6 dvojic ----------

    private fun startPexeso() {
        cards = (symbols + symbols).shuffled()
        open.clear(); found.clear(); tries = 0; busy = false
        pexeso = true
        render()
        speaker.say("Pexeso. Ťukněte na dvě kartičky. Když jsou stejné, zůstanou otočené.")
    }

    private fun pexesoScreen(root: LinearLayout) {
        root.addView(Ui.text(this, "🃏 Pexeso", 28f, bold = true, center = true))
        if (found.size == cards.size) {
            root.addView(Ui.text(this, "🎉 Výborně! Všechno nalezeno.", 26f, bold = true, center = true, color = Ui.OK))
            root.addView(Ui.text(this, "Pokusů: $tries", 22f, center = true))
            root.addView(Ui.button(this, "Hrát znovu", sp = 24f) { startPexeso() })
        } else {
            root.addView(Ui.text(this, "Najděte dvě stejné kartičky.", 20f, center = true, color = Ui.MUTED))
        }
        cards.indices.chunked(3).forEach { rowIdx ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowIdx.forEachIndexed { k, i ->
                val shown = i in open || i in found
                val b: Button = Ui.button(this, if (shown) cards[i] else "❓", if (i in found) Ui.OK_SOFT else Ui.CARD, Ui.INK, sp = 38f) { flip(i) }
                b.minHeight = dp(110)
                row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (k < 2) marginEnd = dp(10) })
            }
            root.addView(row)
        }
        root.addView(Ui.button(this, "Zpět", Ui.BG, Ui.MUTED, sp = 18f) { pexeso = false; render() })
    }

    private fun flip(i: Int) {
        if (busy || i in found || i in open) return
        open += i
        if (open.size == 2) {
            tries++
            val (a, b) = open.toList()
            if (cards[a] == cards[b]) {
                found += open; open.clear()
                render()
                if (found.size == cards.size) speaker.say("Výborně! Všechno nalezeno. Pokusů: $tries.") else speaker.say("Dvojice!")
            } else {
                busy = true
                render()
                window.decorView.postDelayed({ open.clear(); busy = false; if (!isFinishing) render() }, 1300)
            }
        } else render()
    }
}
