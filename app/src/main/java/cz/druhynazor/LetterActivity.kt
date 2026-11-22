package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.druhynazor.Ui.dp
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Modul Dopisy. Senior ťukne na dlaždici Dopis a rovnou se zapne fotoaparát, který sám pozná
 * okraje papíru (Google Document Scanner). Pak aplikace dopis přečte přímo v telefonu a nahlas řekne
 * tři věci: o co jde, jestli je to pravé, co udělat a do kdy. Pod tím Připomenout a Poslat rodině.
 *
 * Nikdy neplatí a nikdy neukazuje QR kód k platbě.
 */
class LetterActivity : Activity() {

    private enum class Screen { SCAN, READING, RESULT, UNREADABLE, MORE, REMIND }

    private var screen = Screen.SCAN
    private lateinit var speaker: Speaker
    private var letter: LetterReader.Letter? = null
    private var saved: LetterStore.Saved? = null
    private var pages: List<Uri> = emptyList()
    private var sentToFamily = false
    private var reminded = false
    private var spoken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val remindId = intent.getStringExtra(EXTRA_REMIND)
        if (remindId != null) {
            val s = LetterStore.get(this, remindId)
            if (s == null) { finish(); return }
            saved = s; screen = Screen.REMIND; render(); return
        }
        if (savedInstanceState == null) scan() else render()
    }

    override fun onPause() { super.onPause(); BackGuide.uiVisible = false; speaker.stop() }
    override fun onResume() { super.onResume(); BackGuide.uiVisible = true }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    private fun say(text: String) {
        if (spoken == text || !Prefs(this).voiceOk) return
        spoken = text
        speaker.say(text)
    }

    // ---------- Fotoaparát ----------

    private fun scan() {
        screen = Screen.SCAN
        spoken = null
        render()
        try {
            scanner().getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    try {
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0)
                    } catch (e: Exception) { scanFailed(e) }
                }
                .addOnFailureListener { scanFailed(it) }
        } catch (e: Exception) { scanFailed(e) }
    }

    private fun scanFailed(e: Exception) {
        Log.w("DruhyNazor", "Fotoaparát na dopisy se nespustil", e)
        screen = Screen.UNREADABLE
        render()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN) return
        if (resultCode != RESULT_OK) { finish(); return } // senior fotoaparát zavřel: zpátky domů, nic se neděje
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        pages = result?.pages?.map { it.imageUri } ?: emptyList()
        if (pages.isEmpty()) { finish(); return }
        screen = Screen.READING
        render()
        readPages()
    }

    // ---------- Čtení ----------

    private fun readPages() {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val texts = arrayOfNulls<String>(pages.size)
        var left = pages.size
        pages.forEachIndexed { i, uri ->
            try {
                recognizer.process(InputImage.fromFilePath(this, uri))
                    .addOnSuccessListener { texts[i] = it.text }
                    .addOnCompleteListener { if (--left == 0) onRead(texts.filterNotNull().joinToString("\n")) }
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Stranu dopisu se nepodařilo přečíst", e)
                if (--left == 0) onRead(texts.filterNotNull().joinToString("\n"))
            }
        }
    }

    private fun onRead(text: String) {
        if (isFinishing) return
        if (text.trim().length < 40) { screen = Screen.UNREADABLE; spoken = null; render(); return }
        var l = LetterReader.read(text)
        l = withSharedList(l)
        letter = l
        saved = LetterStore.save(this, l, pages.firstOrNull())
        screen = Screen.RESULT
        spoken = null
        render()
        // Podvod: rodina se to dozví sama, senior nemusí nic dělat
        if (l.level == ScamCheck.Level.SCAM) sendToFamily(auto = true)
    }

    /** Odkazy a telefony z dopisu ve společném seznamu podvodů (Druhý názor). */
    private fun withSharedList(l: LetterReader.Letter): LetterReader.Letter {
        val extra = mutableListOf<String>()
        for (site in l.sites) {
            val hit = SharedList.webHit(this, site)
            if (hit != null && hit.source != SharedList.Source.COI) extra += "Stránka ${hit.what} je v seznamu podvodných stránek."
            else if (hit != null) extra += "Před stránkou ${hit.what} varuje Česká obchodní inspekce."
        }
        for (phone in l.phones) if (SharedList.isScamNumber(this, phone)) extra += "Telefon $phone je nahlášený jako podvodník."
        if (extra.isEmpty()) return l
        return l.copy(level = ScamCheck.Level.SCAM, reasons = extra + l.reasons.filter { !it.startsWith("Nechce") && !it.contains("známý odesílatel") })
    }

    // ---------- Obrazovky ----------

    private fun render() {
        val root = Ui.column(this, 20, 16).apply { setBackgroundColor(Ui.BG) }
        when (screen) {
            Screen.SCAN -> scanScreen(root)
            Screen.READING -> reading(root)
            Screen.RESULT -> result(root)
            Screen.UNREADABLE -> unreadable(root)
            Screen.MORE -> more(root)
            Screen.REMIND -> remind(root)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun spacer(root: LinearLayout, h: Int = 24) = root.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

    private fun scanScreen(root: LinearLayout) {
        spacer(root, 60)
        root.addView(Ui.text(this, "📄", 80f, center = true))
        root.addView(Ui.text(this, "Namiřte telefon na dopis.", 28f, bold = true, center = true))
        root.addView(Ui.text(this, "Vyfotí se sám. Pak ťukněte dole na Hotovo.", 22f, center = true))
        say("Namiřte telefon na dopis. Vyfotí se sám. Pak ťukněte dole na Hotovo.")
    }

    private fun reading(root: LinearLayout) {
        spacer(root, 80)
        root.addView(Ui.text(this, "📄", 80f, center = true))
        root.addView(Ui.text(this, "Hotovo, mám to.", 30f, bold = true, center = true))
        root.addView(Ui.text(this, "Chvilku, čtu dopis.", 22f, center = true))
        say("Hotovo, mám to. Chvilku, čtu dopis.")
    }

    private fun unreadable(root: LinearLayout) {
        spacer(root, 50)
        root.addView(Ui.text(this, "📄", 72f, center = true))
        root.addView(Ui.text(this, "Dopis se mi nepodařilo přečíst.", 28f, bold = true, center = true))
        val hasPhoto = pages.isNotEmpty()
        val tail = "Teď ťukněte dole na Vyfotit znovu."
        root.addView(Ui.text(this, "Dejte ho na stůl, kde je víc světla. $tail", 22f, center = true))
        say("Dopis se mi nepodařilo přečíst. Dejte ho na stůl, kde je víc světla. $tail")
        spacer(root, 16)
        root.addView(Ui.text(this, "👇", 40f, center = true))
        root.addView(Ui.button(this, "Vyfotit znovu") { pages = emptyList(); scan() })
        if (hasPhoto && Prefs(this).familyContacts.isNotEmpty()) {
            root.addView(Ui.button(this, "Poslat fotku rodině", Ui.CARD, Ui.INK) {
                Mms.sendPhoto(this, pages.first(), "Postaráno: ${Prefs(this).seniorName} posílá fotku dopisu, který jsem nepřečetla.")
                Family.sms(this, "Postaráno: ${Prefs(this).seniorName} vám posílá fotku dopisu. Přijde zvlášť jako MMS. Podívejte se na ni prosím.")
                finish()
            })
        }
        root.addView(Ui.button(this, "Zpět", Ui.BG, Ui.MUTED, sp = 16f) { finish() })
    }

    private fun levelColors(level: ScamCheck.Level) = when (level) {
        ScamCheck.Level.SCAM -> Ui.BAD to Ui.BAD_SOFT
        ScamCheck.Level.WARN -> Ui.WARN to Ui.WARN_SOFT
        ScamCheck.Level.OK -> Ui.OK to Ui.OK_SOFT
    }

    private fun result(root: LinearLayout) {
        val l = letter ?: return finish()
        val prefs = Prefs(this)
        val today = LocalDate.now()
        val about = LetterReader.about(l)
        val verdict = LetterReader.verdict(l)
        val todo = LetterReader.todo(l, today)
        val canRemind = LetterReader.canRemind(l, today) && !reminded
        val family = prefs.familyContacts.isNotEmpty()
        val (strong, soft) = levelColors(l.level)

        // Poslední věta: co ťuknout teď (pravidlo z testů)
        val next = when {
            l.level == ScamCheck.Level.SCAM && sentToFamily -> "${prefs.familyFirstName} už o tom ví. Teď ťukněte dole na Hotovo."
            l.level != ScamCheck.Level.OK && family && !sentToFamily -> "Teď ťukněte dole na Poslat rodině."
            canRemind -> "Teď ťukněte dole na Připomenout."
            reminded -> "Připomenu vám to. Teď ťukněte dole na Hotovo."
            sentToFamily -> "${prefs.familyFirstName} už to má. Teď ťukněte dole na Hotovo."
            else -> "Teď ťukněte dole na Hotovo."
        }

        root.addView(Ui.text(this, ScamCheck.headline(l.level), 30f, bold = true, center = true, color = strong).apply {
            background = Ui.rounded(soft, 18, this@LetterActivity)
            setPadding(dp(12), dp(14), dp(12), dp(14))
        })
        val card = Ui.card(this, 14)
        card.addView(Ui.text(this, about, 24f, bold = true))
        card.addView(Ui.text(this, verdict, 24f, bold = true, color = strong))
        card.addView(Ui.text(this, todo, 24f, bold = true))
        root.addView(card)
        root.addView(Ui.text(this, next, 21f, center = true))
        say("$about $verdict $todo $next")

        root.addView(Ui.text(this, "👇", 40f, center = true))
        if (canRemind) root.addView(Ui.button(this, "⏰  Připomenout", Ui.OK) { remindMe(l) })
        if (family && !sentToFamily) {
            val bg = if (l.level != ScamCheck.Level.OK && !canRemind) Ui.ACCENT else Ui.CARD
            root.addView(Ui.button(this, "👨‍👩‍👧  Poslat rodině", bg, if (bg == Ui.CARD) Ui.INK else android.graphics.Color.WHITE) {
                sendToFamily(auto = false)
            })
        }
        root.addView(Ui.button(this, "Hotovo", if (canRemind || (family && !sentToFamily)) Ui.CARD else Ui.ACCENT,
            if (canRemind || (family && !sentToFamily)) Ui.INK else android.graphics.Color.WHITE) { finish() })
        root.addView(Ui.button(this, "Více", Ui.BG, Ui.MUTED, sp = 17f) { go(Screen.MORE) })
        root.addView(Ui.button(this, "Vyfotit další dopis", Ui.BG, Ui.MUTED, sp = 17f) {
            letter = null; pages = emptyList(); sentToFamily = false; reminded = false; scan()
        })
    }

    private fun more(root: LinearLayout) {
        val l = letter ?: return finish()
        spacer(root, 16)
        root.addView(Ui.text(this, "Více o dopisu", 28f, bold = true, center = true))
        val card = Ui.card(this, 12)
        val lines = LetterReader.more(l)
        lines.forEach { card.addView(Ui.text(this, it, 21f)) }
        root.addView(card)
        say(lines.joinToString(" ") + " Teď ťukněte dole na Zpět.")
        root.addView(Ui.text(this, "Teď ťukněte dole na Zpět.", 20f, center = true))
        root.addView(Ui.button(this, "Zpět") { go(Screen.RESULT) })
    }

    /** Připomínka termínu: ukáže uložený dopis a řekne, co udělat. */
    private fun remind(root: LinearLayout) {
        val s = saved ?: return finish()
        val prefs = Prefs(this)
        spacer(root, 20)
        root.addView(Ui.text(this, "⏰", 72f, center = true))
        root.addView(Ui.text(this, "Připomínám", 30f, bold = true, center = true, color = Ui.ACCENT))
        val card = Ui.card(this, 14)
        card.addView(Ui.text(this, s.about, 24f, bold = true))
        card.addView(Ui.text(this, s.todo(LocalDate.now()), 24f, bold = true))
        root.addView(card)
        photoView(s)?.let { root.addView(it) }
        val next = "Až bude zaplaceno, ťukněte dole na Už je zaplaceno."
        root.addView(Ui.text(this, next, 21f, center = true))
        say("Připomínám. ${s.about} ${s.todo(LocalDate.now())} $next")
        root.addView(Ui.text(this, "👇", 40f, center = true))
        root.addView(Ui.button(this, "✅  Už je zaplaceno", Ui.OK) {
            LetterStore.done(this, s.id); LetterReminder.cancel(this, s.id); finish()
        })
        if (prefs.familyContacts.isNotEmpty()) root.addView(Ui.button(this, "👨‍👩‍👧  Poslat rodině", Ui.CARD, Ui.INK) {
            Family.sms(this, s.familyText)
            s.photo?.let { Mms.sendPhoto(this, Uri.fromFile(File(it)), s.familyText) }
            speaker.say("${prefs.familyFirstName} už to má.")
        })
        root.addView(Ui.button(this, "Později", Ui.BG, Ui.MUTED, sp = 17f) { finish() })
    }

    private fun photoView(s: LetterStore.Saved): View? {
        val path = s.photo ?: return null
        val bmp = try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
        } catch (e: Exception) { null } ?: return null
        return ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            maxHeight = dp(260)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun go(s: Screen) { if (s != screen) speaker.stop(); screen = s; spoken = null; render() }

    // ---------- Tlačítka ----------

    private fun remindMe(l: LetterReader.Letter) {
        val s = saved ?: return
        val at = LetterReminder.schedule(this, s.id, l.due ?: return)
        reminded = true
        spoken = null
        render()
        speaker.say("Připomenu vám to ${LetterReader.date(at)} ráno. Teď ťukněte dole na Hotovo.")
    }

    private fun sendToFamily(auto: Boolean) {
        val s = saved ?: return
        val prefs = Prefs(this)
        if (prefs.familyContacts.isEmpty() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        Family.sms(this, s.familyText)
        if (prefs.ntfyEnabled) Family.ntfy(this, "Dopis: " + ScamCheck.headline(s.level), s.familyText, s.level == ScamCheck.Level.SCAM)
        s.photo?.let { Mms.sendPhoto(this, Uri.fromFile(File(it)), s.familyText) }
        sentToFamily = true
        spoken = null
        render()
        if (!auto) speaker.say("${prefs.familyFirstName} už to má. Teď ťukněte dole na Hotovo.")
    }

    companion object {
        const val EXTRA_REMIND = "pripominka"
        private const val REQ_SCAN = 41

        fun scanner(): GmsDocumentScanner = GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(5)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build())

        /** Fotoaparát na dopisy se stahuje do telefonu zvlášť. Stáhnout předem, ať senior nečeká. */
        fun prepare(a: Activity) {
            try {
                ModuleInstall.getClient(a).installModules(ModuleInstallRequest.newBuilder().addApi(scanner()).build())
                    .addOnFailureListener { Log.w("DruhyNazor", "Fotoaparát na dopisy se nestáhl", it) }
            } catch (e: Exception) { Log.w("DruhyNazor", "Fotoaparát na dopisy se nestáhl", e) }
        }
    }
}
