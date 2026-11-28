package cz.druhynazor

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.VideoView
import cz.druhynazor.Ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Od rodiny: fotka, hlasový nebo psaný vzkaz přes celou obrazovku, nahlas.
 * Senior odpoví srdíčkem nebo zavolá. Dole je album všeho, co přišlo za posledních 30 dní.
 */
class FamilyMailActivity : Activity() {

    private lateinit var speaker: Speaker
    private var player: MediaPlayer? = null
    private var videoView: VideoView? = null
    private var currentId: String? = null
    private var album = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(i: Intent?) {
        FamilyMail.clearNotification(this)
        album = i?.getBooleanExtra(EXTRA_ALBUM, false) == true
        val items = FamilyMail.items(this)
        currentId = i?.getStringExtra(EXTRA_ID) ?: items.firstOrNull { !it.seen }?.id
        if (currentId == null) album = true
        render(speak = true)
        // Když je otevřené, podívat se rovnou na server, jestli něco nepřibylo
        FamilyMail.checkNow(this) { n -> if (n > 0 && album) Ui.whenIdle(this) { if (album) render(speak = false, keep = true) } }
    }

    override fun onPause() {
        super.onPause()
        speaker.stop()
        player?.pause()
        videoView?.pause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        speaker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (!album) { stopPlayer(); album = true; render(speak = false) }
        else { @Suppress("DEPRECATION") super.onBackPressed() }
    }

    private fun stopPlayer() {
        player?.release(); player = null
        videoView?.stopPlayback(); videoView = null
    }

    private fun render(speak: Boolean, keep: Boolean = false) {
        val root = Ui.column(this, 20, 16).apply { setBackgroundColor(Ui.BG) }
        val item = if (album) null else FamilyMail.items(this).firstOrNull { it.id == currentId }
        if (item == null) albumScreen(root, speak) else itemScreen(root, item, speak)
        Ui.show(this, root, keep = keep)
    }

    // ---------- Jedna věc ----------

    private fun itemScreen(root: LinearLayout, item: MailItem, speak: Boolean) {
        FamilyMail.markSeen(this, item.id)
        val heading = when {
            item.isPhoto -> "📷 Fotka"
            item.isVoice -> "🎤 Hlasový vzkaz"
            item.isVideo -> "🎬 Video"
            else -> "✍️ Vzkaz"
        }
        root.addView(Ui.text(this, heading, 30f, bold = true, center = true))
        root.addView(Ui.text(this, "Posílá ${item.from}, ${day(item.time)}", 22f, center = true, color = Ui.MUTED))

        if (item.isPhoto) {
            val f = FamilyMail.file(this, item.id)
            val bmp = if (f.exists()) decode(f.path) else null
            if (bmp != null) {
                root.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else root.addView(Ui.text(this, "Fotku se nepodařilo otevřít.", 20f, center = true, color = Ui.WARN))
        }
        if (item.isVideo) {
            val f = FamilyMail.file(this, item.id)
            if (f.exists()) {
                stopPlayer()
                val v = VideoView(this).apply {
                    setVideoPath(f.path)
                    setOnPreparedListener { mp -> mp.isLooping = false }
                    setOnErrorListener { _, _, _ ->
                        speaker.say("Video se nepodařilo přehrát."); true
                    }
                    setOnClickListener { if (isPlaying) pause() else start() }
                }
                videoView = v
                // Výška podle šířky displeje (16:9 se vejde, na výšku natočené se zmenší)
                val h = (resources.displayMetrics.heightPixels * 0.55).toInt()
                root.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { gravity = Gravity.CENTER })
                root.addView(Ui.button(this, "▶  Pustit znovu", sp = 26f) { speaker.stop(); v.seekTo(0); v.start() }.apply { minHeight = dp(110) })
            } else root.addView(Ui.text(this, "Video se nepodařilo otevřít.", 20f, center = true, color = Ui.WARN))
        }
        if (item.caption.isNotBlank()) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, item.caption, if (item.kind == "text") 30f else 26f, bold = item.kind == "text"))
            root.addView(card)
        }
        if (item.isVoice) {
            root.addView(Ui.button(this, "▶  Pustit znovu", sp = 26f) { play(item) }.apply { minHeight = dp(110) })
        }

        // Odpověď
        if (item.heart) {
            root.addView(Ui.text(this, "❤️ Srdíčko odešlo.", 24f, bold = true, center = true, color = Ui.OK))
        } else {
            root.addView(Ui.button(this, "❤️  Poslat srdíčko", Ui.CARD, Ui.BAD, sp = 26f) {
                FamilyMail.markSeen(this, item.id, heart = true)
                speaker.say("Srdíčko odešlo.")
                render(speak = false)
            }.apply { minHeight = dp(100) })
        }
        val contact = Prefs(this).familyContacts.firstOrNull { it.name.equals(item.from, ignoreCase = true) }
        if (contact != null) {
            root.addView(Ui.button(this, "📞  Zavolat: ${contact.name}", Ui.OK, sp = 24f) {
                stopPlayer()
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Family.cleanNumber(contact.phone))))
            }.apply { minHeight = dp(90) })
        }
        val next = FamilyMail.items(this).firstOrNull { !it.seen && it.id != item.id }
        if (next != null) {
            root.addView(Ui.button(this, "Další ▶", sp = 24f) { stopPlayer(); currentId = next.id; render(speak = true) })
        }
        root.addView(Ui.button(this, "🖼️  Všechno od rodiny", Ui.CARD, Ui.ACCENT, sp = 19f) {
            stopPlayer(); album = true; render(speak = false)
        })
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })

        if (speak) {
            val what = when {
                item.isPhoto -> "Fotka. Posílá ${item.from}. ${item.caption}"
                item.isVoice -> "Hlasový vzkaz. Posílá ${item.from}."
                item.isVideo -> "Video. Posílá ${item.from}. ${item.caption}"
                else -> "Vzkaz. Posílá ${item.from}. ${item.caption}"
            }
            speaker.say(what)
            // Hlas rodiny pustit až po krátkém ohlášení
            if (item.isVoice) window.decorView.postDelayed({ if (!isFinishing && currentId == item.id && !album) play(item) }, 3000)
            if (item.isVideo) window.decorView.postDelayed({
                if (!isFinishing && currentId == item.id && !album) { speaker.stop(); videoView?.start() }
            }, 3000 + item.caption.length * 60L)
        }
    }

    private fun play(item: MailItem) {
        speaker.stop()
        stopPlayer()
        val f = FamilyMail.file(this, item.id)
        if (!f.exists()) return
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.w("DruhyNazor", "Od rodiny: vzkaz nejde přehrát", e)
            speaker.say("Vzkaz se nepodařilo přehrát.")
        }
    }

    private fun decode(path: String): android.graphics.Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    private fun day(t: Long): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US)
        val now = Date()
        return when (today.format(Date(t))) {
            today.format(now) -> "dnes v " + SimpleDateFormat("H:mm", Locale("cs")).format(Date(t))
            today.format(Date(now.time - 24 * 3600 * 1000)) -> "včera"
            else -> SimpleDateFormat("d. M.", Locale("cs")).format(Date(t))
        }
    }

    // ---------- Album ----------

    private fun albumScreen(root: LinearLayout, speak: Boolean) {
        val items = FamilyMail.items(this).sortedByDescending { it.time }
        root.addView(Ui.text(this, "📷 Od rodiny", 32f, bold = true, center = true))
        if (items.isEmpty()) {
            val line = "Zatím nic nepřišlo. Až vám rodina pošle fotku nebo vzkaz, ukáže se tady."
            root.addView(Ui.text(this, line, 22f, center = true))
            if (speak) speaker.say(line)
        } else {
            root.addView(Ui.text(this, "Ťukněte na to, co chcete vidět.", 20f, center = true, color = Ui.MUTED))
            if (speak) speaker.say("Všechno od rodiny. Ťukněte na to, co chcete vidět.")
            for (it in items) root.addView(row(it))
        }
        // Proti samotě: popovídat si s člověkem (zdarma, denně 8 až 20)
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(12)))
        root.addView(Ui.button(this, "☎️  Popovídat si: Linka seniorů (zdarma)", Ui.CARD, Ui.ACCENT, sp = 18f) {
            Linka.call(this)
        })
        root.addView(Ui.text(this, "Linka seniorů 800 200 007, každý den od 8 do 20 hodin, zdarma.", 16f, center = true, color = Ui.MUTED))
        root.addView(Ui.button(this, "Zavřít", Ui.BG, Ui.MUTED, sp = 18f) { finish() })
    }

    private fun row(item: MailItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (item.seen) Ui.CARD else Ui.ACCENT_SOFT, 18, this@FamilyMailActivity, Ui.LINE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { currentId = item.id; album = false; render(speak = true) }
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        if (item.isPhoto) {
            val f = FamilyMail.file(this, item.id)
            val b = try {
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            } catch (e: Exception) { null }
            if (b != null) thumb.setImageBitmap(b)
            row.addView(thumb, LinearLayout.LayoutParams(dp(90), dp(90)).apply { marginEnd = dp(12) })
        } else {
            row.addView(Ui.text(this, when { item.isVoice -> "🎤"; item.isVideo -> "🎬"; else -> "✍️" }, 44f), LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        }
        val label = (if (item.seen) "" else "🔴 Nové\n") + item.from + "\n" + day(item.time)
        row.addView(Ui.text(this, label, 20f, bold = !item.seen), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ALBUM = "album"
    }
}

/** Linka seniorů (Elpida): bezplatná, anonymní, každý den 8–20 h. Ověřeno na elpida.cz 28. 11. 2026. */
object Linka {
    const val NUMBER = "800200007"
    fun call(a: Activity) {
        try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$NUMBER"))) } catch (_: Exception) { }
    }
}
