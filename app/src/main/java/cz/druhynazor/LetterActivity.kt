package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * tři věci: o co jde, jestli to není podvod, co udělat a do kdy. Pod tím Připomenout a Poslat rodině.
 *
 * Nikdy neplatí a nikdy neukazuje QR kód k platbě.
 */
class LetterActivity : Activity() {

    private enum class Screen { SCAN, CHOOSE, READING, RESULT, UNREADABLE, MORE, REMIND, NOTICE }

    private var screen = Screen.SCAN
    private lateinit var speaker: Speaker
    private var letter: LetterReader.Letter? = null
    private var saved: LetterStore.Saved? = null
    private var pages: List<Uri> = emptyList()
    private var sentToFamily = false
    private var waOpened = false
    /** Odesílání rodině: null = nic, true = probíhá. */
    private var sending = false
    private var sendFailed = false
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
        if (savedInstanceState != null) { render(); return }
        // Dopis poslaný z jiné aplikace (Fotky, Soubory, e-mail): rovnou přečíst
        val shared = sharedUris(intent)
        // Poprvé: upozornění, že dopis čte umělá inteligence, a volba
        if (AiLetters.available(this) && Prefs(this).letterMode < 0) {
            pendingShared = shared; screen = Screen.NOTICE; render(); return
        }
        start(shared)
    }

    private var pendingShared: List<Uri> = emptyList()

    private fun start(shared: List<Uri>) { if (shared.isNotEmpty()) openFiles(shared) else scan() }

    /** Upozornění při prvním otevření Dopisu: kdo dopis čte a že se může splést. */
    private fun notice(root: LinearLayout) {
        spacer(root, 24)
        root.addView(Ui.text(this, "📄", 64f, center = true))
        val head = "Dopis čte umělá inteligence. Může se splést. U peněz se vždycky poraďte s rodinou."
        root.addView(Ui.text(this, head, 24f, bold = true, center = true))
        root.addView(Ui.text(this, "Pošle se jen text dopisu, ne fotka, a bez rodných čísel. Přečte ho Claude od firmy Anthropic. Změnit to jde v Pro rodinu.", 19f, center = true, color = Ui.MUTED))
        say("$head Teď ťukněte dole na Rozumím a souhlasím.")
        spacer(root, 12)
        root.addView(Ui.button(this, "Rozumím a souhlasím") { pickMode(AiLetters.MODE_BASIC) })
        root.addView(Ui.button(this, "Nechci, číst jen v telefonu", Ui.CARD, Ui.INK, sp = 18f) { pickMode(AiLetters.MODE_PHONE) })
    }

    private fun pickMode(mode: Int) {
        Prefs(this).letterMode = mode
        speaker.stop()
        start(pendingShared)
    }

    private fun sharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        @Suppress("DEPRECATION")
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
    }

    // ---------- Z Fotek nebo Souborů ----------

    /** Výběr fotky nebo PDF (pro zdatnější, senior má velký fotoaparát). */
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Výběr souboru se neotevřel", e)
        }
    }

    /** Fotky se čtou rovnou, PDF se nejdřív převede na obrázky stran (nejvýš 5 stran). */
    private fun openFiles(uris: List<Uri>) {
        screen = Screen.READING
        spoken = null
        render()
        thread(name = "dopis-soubory") {
            val out = mutableListOf<Uri>()
            for (u in uris) {
                if (out.size >= MAX_PAGES) break
                val type = try { contentResolver.getType(u) } catch (e: Exception) { null } ?: ""
                if (type == "application/pdf" || u.toString().lowercase().endsWith(".pdf")) out += pdfPages(u, MAX_PAGES - out.size)
                else out += u
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                pages = out
                if (pages.isEmpty()) { screen = Screen.UNREADABLE; spoken = null; render() } else readPages()
            }
        }
    }

    private fun pdfPages(u: Uri, max: Int): List<Uri> = try {
        val dir = File(cacheDir, "dopis-pdf").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        contentResolver.openFileDescriptor(u, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, max)).map { n ->
                    pdf.openPage(n).use { page ->
                        // Asi 200 DPI: dost ostré na čtení, ne moc velké
                        val scale = 2000f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, "strana-${System.currentTimeMillis()}-$n.jpg")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        bmp.recycle()
                        Uri.fromFile(f)
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.w("DruhyNazor", "PDF se nepodařilo otevřít", e)
        emptyList()
    }

    override fun onPause() { super.onPause(); BackGuide.uiVisible = false; speaker.stop() }
    override fun onResume() {
        super.onResume(); BackGuide.uiVisible = true
        // Návrat z WhatsAppu: říct až to, co platí teď
        if (waOpened && screen == Screen.RESULT) { spoken = null; render() }
    }
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
        if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null) { if (letter == null) go(Screen.CHOOSE); return }
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            if (uris.isNotEmpty()) { resetLetter(); openFiles(uris) }
            return
        }
        if (requestCode != REQ_SCAN) return
        // Fotoaparát zavřený: nabídnout znovu vyfotit, vybrat z Fotek nebo Souborů, nebo Zpět
        if (resultCode != RESULT_OK) { go(Screen.CHOOSE); return }
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        pages = result?.pages?.map { it.imageUri } ?: emptyList()
        if (pages.isEmpty()) { go(Screen.CHOOSE); return }
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
        if (AiLetters.shouldUse(this)) {
            thread(name = "dopis-ai") {
                val hints = AiLetters.ask(this, text)
                runOnUiThread { showLetter(text, hints) }
            }
        } else showLetter(text, null)
    }

    private fun showLetter(text: String, hints: LetterReader.AiHints?) {
        if (isFinishing) return
        var l = LetterReader.read(text, LocalDate.now(), hints)
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
            Screen.CHOOSE -> choose(root)
            Screen.READING -> reading(root)
            Screen.RESULT -> result(root)
            Screen.UNREADABLE -> unreadable(root)
            Screen.MORE -> more(root)
            Screen.REMIND -> remind(root)
            Screen.NOTICE -> notice(root)
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

    private fun choose(root: LinearLayout) {
        spacer(root, 50)
        root.addView(Ui.text(this, "📄", 80f, center = true))
        root.addView(Ui.text(this, "Přečtu vám dopis.", 28f, bold = true, center = true))
        val next = "Teď ťukněte dole na Vyfotit dopis."
        root.addView(Ui.text(this, next, 22f, center = true))
        say("Přečtu vám dopis. $next")
        spacer(root, 16)
        root.addView(Ui.text(this, "👇", 40f, center = true))
        root.addView(Ui.button(this, "📷  Vyfotit dopis") { resetLetter(); scan() })
        root.addView(Ui.button(this, "🖼️  Vybrat z Fotek nebo Souborů", Ui.CARD, Ui.INK, sp = 18f) { pick() })
        root.addView(Ui.button(this, "Zpět", Ui.BG, Ui.MUTED, sp = 17f) { finish() })
    }

    private fun resetLetter() { letter = null; saved = null; pages = emptyList(); sentToFamily = false; waOpened = false; sending = false; sendFailed = false; reminded = false }

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
        root.addView(Ui.button(this, "Vybrat z Fotek nebo Souborů", Ui.CARD, Ui.INK, sp = 18f) { pick() })
        if (hasPhoto && Prefs(this).familyContacts.isNotEmpty()) {
            waButtons(root, pages.first(), "Posílám fotku dopisu, Postaráno ho nepřečetlo. Podívejte se na něj prosím.")
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
        val waReady = family && saved?.photo != null && WhatsAppPhoto.app(this) != null

        // Poslední věta: co ťuknout teď (pravidlo z testů)
        val next = when {
            sending -> "Posílám to rodině, chvilku počkejte."
            sendFailed -> "Zprávu rodině se nepodařilo odeslat. Teď ťukněte dole na Zkusit znovu."
            waOpened -> "Teď ťukněte dole na Hotovo."
            sentToFamily && waReady -> "${prefs.familyFirstName} už má zprávu. Pokud chcete poslat i fotku dopisu, ťukněte dole na modré tlačítko WhatsApp. Jinak ťukněte na Hotovo."
            l.level == ScamCheck.Level.SCAM && sentToFamily -> "${prefs.familyFirstName} už o tom ví. Teď ťukněte dole na Hotovo."
            (l.level != ScamCheck.Level.OK || l.big) && family && !sentToFamily -> "Teď ťukněte dole na Poslat rodině."
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
        // Po ťuknutí na Poslat rodině říct jen novinku, ne znovu celý dopis
        say(if (sending || sendFailed || sentToFamily || reminded || waOpened) next else "$about $verdict $todo $next")

        root.addView(Ui.text(this, "👇", 40f, center = true))
        if (canRemind) root.addView(Ui.button(this, "⏰  Připomenout", Ui.OK) { remindMe(l) })
        if (family && sendFailed) {
            root.addView(Ui.text(this, "Zkontrolujte, jestli má telefon signál a kredit.", 19f, center = true, color = Ui.WARN))
            Family.lastProblem?.let { root.addView(Ui.text(this, "Důvod: $it", 16f, center = true, color = Ui.MUTED)) }
            root.addView(Ui.button(this, "🔁  Zkusit znovu", Ui.WARN) { sendToFamily(auto = false) })
        } else if (family && !sentToFamily && !sending) {
            val bg = if ((l.level != ScamCheck.Level.OK || l.big) && !canRemind) Ui.ACCENT else Ui.CARD
            root.addView(Ui.button(this, "👨‍👩‍👧  Poslat rodině", bg, if (bg == Ui.CARD) Ui.INK else android.graphics.Color.WHITE) {
                sendToFamily(auto = false)
            })
        }
        // Fotka přes WhatsApp (zdarma) až po SMS, nebo místo ní, když SMS neodešla
        if (waReady && !sending && (sentToFamily || sendFailed)) {
            saved?.photo?.let { waButtons(root, Uri.fromFile(File(it)), saved!!.familyText) }
        }
        root.addView(Ui.button(this, "Hotovo", if (canRemind || (family && !sentToFamily)) Ui.CARD else Ui.ACCENT,
            if (canRemind || (family && !sentToFamily)) Ui.INK else android.graphics.Color.WHITE) { finish() })
        root.addView(Ui.button(this, "Více", Ui.BG, Ui.MUTED, sp = 17f) { go(Screen.MORE) })
        root.addView(Ui.button(this, "Vyfotit další dopis", Ui.BG, Ui.MUTED, sp = 17f) { resetLetter(); scan() })
        root.addView(Ui.button(this, "Vybrat z Fotek nebo Souborů", Ui.BG, Ui.MUTED, sp = 17f) { pick() })
    }

    private fun more(root: LinearLayout) {
        val l = letter ?: return finish()
        spacer(root, 16)
        root.addView(Ui.text(this, "Více o dopisu", 28f, bold = true, center = true))
        val card = Ui.card(this, 12)
        val lines = LetterReader.more(l) + listOfNotNull(
            if (l.aiModel != null) "Dopis četla umělá inteligence a telefon to zkontroloval." else AiLetters.lastProblem,
        )
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
            speaker.say("Posílám to rodině.")
            Family.sms(this, s.familyText, maxParts = 2) { ok ->
                speaker.say(if (ok) "${prefs.familyFirstName} už to má." else "Zprávu rodině se nepodařilo odeslat. Zkontrolujte, jestli má telefon signál a kredit.")
            }
        })
        if (prefs.familyContacts.isNotEmpty()) s.photo?.let { waButtons(root, Uri.fromFile(File(it)), s.familyText) }
        root.addView(Ui.button(this, "Později", Ui.BG, Ui.MUTED, sp = 17f) { finish() })
    }

    /**
     * Tlačítko (u více lidí z rodiny tlačítka) Poslat fotku přes WhatsApp. MMS stojí peníze,
     * WhatsApp ne. Poslat musí senior sám ťuknutím na zelenou šipku, to jinak nejde.
     */
    private fun waButtons(root: LinearLayout, photo: Uri, text: String) {
        if (WhatsAppPhoto.app(this) == null) return
        val contacts = Prefs(this).familyContacts.take(3)
        for (c in contacts) {
            val label = if (contacts.size == 1) "💬  WhatsApp: poslat fotku" else "💬  WhatsApp: fotka pro ${c.name}"
            root.addView(Ui.button(this, label, Ui.BLUE) {
                waOpened = true
                speaker.say("Ve WhatsAppu ťukněte na zelenou šipku.")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    if (!WhatsAppPhoto.open(this, photo, text, c)) {
                        waOpened = false
                        speaker.say("WhatsApp se nepodařilo otevřít.")
                    }
                }, 1500)
            })
        }
        root.addView(Ui.text(this, "Otevře se WhatsApp. Tam ťukněte na zelenou šipku.", 17f, center = true, color = Ui.MUTED))
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
        if (prefs.familyContacts.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DruhyNazor", "Dopis rodině: chybí povolení posílat SMS")
            Family.lastProblem = "Postaráno nemá povolení posílat SMS"
            sendFailed = true; spoken = null; render(); return
        }
        sending = true; sendFailed = false; spoken = null
        render()
        Family.sms(this, s.familyText, maxParts = 2) { ok ->
            sending = false
            if (ok) {
                sentToFamily = true
            } else sendFailed = true
            if (isFinishing || isDestroyed) return@sms
            // render() sám řekne, co ťuknout teď (nic dalšího, ať hlas nemluví přes sebe)
            spoken = null
            render()
        }
    }

    companion object {
        const val EXTRA_REMIND = "pripominka"
        private const val REQ_SCAN = 41
        private const val REQ_PICK = 42
        private const val MAX_PAGES = 5

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
