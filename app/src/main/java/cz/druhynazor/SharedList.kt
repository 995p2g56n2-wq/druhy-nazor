package cz.druhynazor

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Společný seznam podvodů pro všechny, kdo mají Postaráno.
 *
 * 1. Mezi námi: když rodina u varování potvrdí „Je to podvod“, nahlásí se číslo odesílatele a stránky
 *    z odkazů (nic jiného: žádný text zprávy, žádné jméno, žádné číslo seniora). Do společného seznamu
 *    se dostane až to, co nahlásí aspoň 3 různé telefony (proti falešnému nahlašování).
 *    Vlastní rodina platí hned: číslo, které rodina označila za podvod, tenhle telefon zná okamžitě.
 * 2. Z internetu: seznam rizikových e-shopů České obchodní inspekce a světový seznam podvodných
 *    stránek (Phishing.Database). Stahuje je a skládá server, telefon si bere hotový seznam.
 *
 * Server = sestavení na GitHubu, které každých 15 minut přečte nová hlášení (schránka ntfy.sh),
 * spočítá je a zveřejní seznam ve větvi „seznam“ tohoto projektu.
 */
object SharedList {

    private const val REPORT_URL = "https://ntfy.sh/postarano-hlaseni-v1"
    private const val LIST_URL = "https://raw.githubusercontent.com/995p2g56n2-wq/druhy-nazor/seznam/"
    private const val HOUR = 3600_000L
    private const val DAY = 24 * HOUR

    enum class Source { FAMILY, COMMUNITY, COI, PHISH }

    data class Hit(val source: Source, val what: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("spolecny_seznam", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "seznam").apply { mkdirs() }

    // ---------- Kontrola ----------

    @Volatile private var numbers: Set<String>? = null
    @Volatile private var webs: Map<String, Source>? = null
    @Volatile private var phish: ByteArray? = null

    private fun loadNumbers(ctx: Context): Set<String> = numbers ?: (try {
        File(dir(ctx), "cisla.txt").takeIf { it.exists() }?.readLines()
            ?.map { it.trim() }?.filter { it.length == 9 && it.all(Char::isDigit) }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }).also { numbers = it }

    /** „komunita host“ / „coi host“ → host → zdroj. */
    private fun loadWebs(ctx: Context): Map<String, Source> = webs ?: (try {
        File(dir(ctx), "weby.txt").takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 2) null else when (parts[0]) {
                "komunita" -> parts[1] to Source.COMMUNITY
                "coi" -> parts[1] to Source.COI
                else -> null
            }
        }?.toMap() ?: emptyMap()
    } catch (e: Exception) { emptyMap() }).also { webs = it }

    private fun loadPhish(ctx: Context): ByteArray = phish ?: (try {
        File(dir(ctx), "phish.bin").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
    } catch (e: Exception) { ByteArray(0) }).also { phish = it }

    private fun familyNumbers(ctx: Context): Set<String> = prefs(ctx).getStringSet("rodina_cisla", null) ?: emptySet()
    private fun familyWebs(ctx: Context): Set<String> = prefs(ctx).getStringSet("rodina_weby", null) ?: emptySet()

    /** Číslo je podvodné: potvrdila ho vlastní rodina, společný seznam, nebo je v nastavení pro zkoušení. */
    fun isScamNumber(ctx: Context, number: String?): Boolean = numberHit(ctx, number) != null || Prefs(ctx).isTestScamNumber(number)

    fun numberHit(ctx: Context, number: String?): Hit? {
        val n = reportableNumber(number) ?: return null
        if (n in familyNumbers(ctx)) return Hit(Source.FAMILY, n)
        if (n in loadNumbers(ctx)) return Hit(Source.COMMUNITY, n)
        return null
    }

    /** První odkaz ze zprávy, který je v některém seznamu podvodných stránek. */
    fun webHit(ctx: Context, text: String): Hit? {
        val own = familyWebs(ctx)
        val list = loadWebs(ctx)
        for (link in ScamCheck.links(text)) {
            val key = reportKey(link)
            if (key in own) return Hit(Source.FAMILY, link.host)
            list[key]?.let { return Hit(it, link.host) }
            // ČOI uvádí hlavní adresu e-shopu, odkaz může vést na její podstránku
            var h = link.host
            while (h.count { it == '.' } >= 1) {
                if (list[h] == Source.COI) return Hit(Source.COI, link.host)
                h = h.substringAfter('.')
            }
            if (!isProtected(link.host) && inPhish(ctx, link.host)) return Hit(Source.PHISH, link.host)
        }
        return null
    }

    private fun inPhish(ctx: Context, host: String): Boolean {
        val data = loadPhish(ctx)
        val n = data.size / 5
        if (n == 0) return false
        val h = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(Charsets.UTF_8))
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            var c = 0
            for (i in 0 until 5) {
                c = (data[mid * 5 + i].toInt() and 0xFF) - (h[i].toInt() and 0xFF)
                if (c != 0) break
            }
            when {
                c == 0 -> return true
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return false
    }

    /**
     * Kontrola zprávy včetně společného seznamu. Nález v seznamu má přednost i před „reklamou
     * od známého odesílatele“ (jméno odesílatele se dá napodobit).
     */
    fun check(ctx: Context, text: String, sender: String?): ScamCheck.Result {
        val base = if (sender != null) TrustedSenders.check(ctx, text, sender) else ScamCheck.check(text)
        val num = numberHit(ctx, sender)
        val web = webHit(ctx, text)
        val (level, sign, reason) = when {
            num?.source == Source.FAMILY -> Triple(ScamCheck.Level.SCAM, "Nahlášené číslo",
                "Toto číslo už rodina jednou označila jako podvod.")
            num != null -> Triple(ScamCheck.Level.SCAM, "Nahlášené číslo",
                "Toto číslo už jako podvod nahlásilo několik rodin.")
            web == null -> return base
            web.source == Source.FAMILY -> Triple(ScamCheck.Level.SCAM, "Nahlášená stránka",
                "Stránku ${web.what} už rodina jednou označila jako podvod.")
            web.source == Source.COMMUNITY -> Triple(ScamCheck.Level.SCAM, "Nahlášená stránka",
                "Stránku ${web.what} už jako podvod nahlásilo několik rodin.")
            web.source == Source.PHISH -> Triple(ScamCheck.Level.SCAM, "Podvodná stránka",
                "Stránka ${web.what} je ve světovém seznamu podvodných stránek.")
            else -> Triple(if (base.level == ScamCheck.Level.SCAM) ScamCheck.Level.SCAM else ScamCheck.Level.WARN,
                "Rizikový e-shop", "Před stránkou ${web.what} varuje Česká obchodní inspekce. Může jít o podvodný obchod.")
        }
        return ScamCheck.Result(level, reason, listOf(sign) + base.signs.filter { it != "Reklama" })
    }

    // ---------- Hlášení ----------

    /** Krátké odkazy a sdílené stránky: hlásí se celý odkaz, ne celá stránka (jinak by se zablokoval např. bit.ly). */
    private val sharedHosts = setOf(
        "bit.ly", "tinyurl.com", "t.ly", "is.gd", "cutt.ly", "shorturl.at", "rb.gy", "goo.gl", "ow.ly", "buff.ly",
        "tiny.cc", "s.id", "t.co", "wa.me", "t.me", "linktr.ee", "qrco.de", "surl.li", "u.to", "clck.ru",
        "sites.google.com", "docs.google.com", "forms.gle", "drive.google.com", "storage.googleapis.com",
        "firebasestorage.googleapis.com", "dropbox.com", "onedrive.live.com", "1drv.ms", "temu.to"
    )

    /** Velké a úřední stránky, které se do seznamu nikdy nedostanou (ochrana před omylem i zneužitím). */
    private val protectedSites = setOf(
        "google.com", "google.cz", "youtube.com", "youtu.be", "facebook.com", "fb.com", "instagram.com", "whatsapp.com",
        "apple.com", "icloud.com", "microsoft.com", "live.com", "outlook.com", "seznam.cz", "email.cz", "mapy.cz",
        "gov.cz", "mojedatovaschranka.cz", "policie.cz", "cnb.cz", "coi.gov.cz", "csob.cz", "kb.cz", "csas.cz",
        "rb.cz", "airbank.cz", "fio.cz", "moneta.cz", "mbank.cz", "unicreditbank.cz", "creditas.cz",
        "ceskaposta.cz", "postaonline.cz", "zasilkovna.cz", "packeta.com", "ppl.cz", "dpd.cz", "gls-group.eu",
        "alza.cz", "cez.cz", "o2.cz", "t-mobile.cz", "vodafone.cz", "idnes.cz", "novinky.cz", "ceskatelevize.cz"
    )

    fun isProtected(host: String): Boolean =
        protectedSites.any { host == it || host.endsWith(".$it") }

    /** „stránka“ nebo „stránka/cesta“ u krátkých odkazů. */
    private fun reportKey(link: ScamCheck.Link): String =
        if (link.host in sharedHosts && link.path.length > 1) (link.host + link.path).take(200) else link.host

    /** Číslo, které má smysl hlásit: běžný telefon (9 číslic), ne jméno odesílatele ani krátké číslo. */
    fun reportableNumber(number: String?): String? {
        if (number.isNullOrBlank() || ScamCheck.isServiceSender(number)) return null
        if (number.any { it.isLetter() }) return null
        val n = Prefs.normalize(number)
        return if (n.length == 9) n else null
    }

    /** Co zpráva obsahuje k nahlášení: číslo odesílatele a stránky z odkazů. Ukládá se, když se ptáme rodiny. */
    fun rememberSubject(ctx: Context, key: String, sender: String?, text: String) {
        val o = JSONObject()
        val prefsMain = Prefs(ctx)
        reportableNumber(sender)?.takeIf { prefsMain.familyByNumber(sender!!) == null }?.let { o.put("n", it) }
        val webs = JSONArray()
        ScamCheck.links(text).map { reportKey(it) }.distinct()
            .filter { it.contains('/') || !isProtected(it) }.take(5).forEach { webs.put(it) }
        if (webs.length() > 0) o.put("w", webs)
        if (o.length() == 0) return
        prefs(ctx).edit().putString("subj_$key", o.toString()).apply()
    }

    /** Rodina rozhodla: zapamatovat si to v telefonu a nahlásit do společného seznamu. Vrací true, když bylo co hlásit. */
    fun onVerdict(ctx: Context, key: String, scam: Boolean): Boolean {
        val p = prefs(ctx)
        if (p.getString("subj_$key", null) == null && key.startsWith("s")) {
            // Zpráva z doby před touto verzí: obsah vezmeme z uložené SMS
            key.drop(1).toLongOrNull()?.let { SmsStore.byId(ctx, it) }?.let { rememberSubject(ctx, key, it.address, it.body) }
        }
        val raw = p.getString("subj_$key", null) ?: return false
        val o = try { JSONObject(raw) } catch (e: Exception) { return false }
        val num = o.optString("n").takeIf { it.isNotEmpty() }
        val webArr = o.optJSONArray("w")
        val webs = (0 until (webArr?.length() ?: 0)).map { webArr!!.getString(it) }

        // Vlastní rodina platí hned
        fun update(name: String, items: Collection<String>) {
            val now = (p.getStringSet(name, null) ?: emptySet()).toMutableSet()
            if (scam) now += items else now -= items.toSet()
            p.edit().putStringSet(name, now).apply()
        }
        num?.let { update("rodina_cisla", listOf(it)) }
        update("rodina_weby", webs)

        val queue = JSONArray(p.getString("fronta", null) ?: "[]")
        num?.let { queue.put(JSONObject().put("t", "n").put("x", it).put("r", if (scam) "s" else "o")) }
        webs.forEach { queue.put(JSONObject().put("t", "w").put("x", it).put("r", if (scam) "s" else "o")) }
        p.edit().putString("fronta", queue.toString()).apply()
        sendQueue(ctx)
        return num != null || webs.isNotEmpty()
    }

    /** Náhodné označení tohoto telefonu. Server podle něj pozná, že hlášení jsou od různých telefonů. Nic o člověku neříká. */
    private fun installId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("id", null) ?: (1..20).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }
            .joinToString("").also { p.edit().putString("id", it).apply() }
    }

    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Malý výpočet (zlomek vteřiny), aby se nedalo snadno poslat tisíce falešných hlášení. */
    private fun work(item: String, id: String): String {
        var nonce = 0
        val md = MessageDigest.getInstance("SHA-256")
        while (true) {
            val d = md.digest("$item|$id|$nonce".toByteArray(Charsets.UTF_8))
            if (d[0].toInt() == 0 && d[1].toInt() == 0) return nonce.toString()
            nonce++
        }
    }

    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun sendQueue(ctx: Context) {
        val app = ctx.applicationContext
        if (!sending.compareAndSet(false, true)) return
        thread(name = "seznam-hlaseni") {
            try {
                val p = prefs(app)
                val queue = JSONArray(p.getString("fronta", null) ?: "[]")
                val id = installId(app)
                var sent = 0
                for (i in 0 until queue.length()) {
                    val q = queue.getJSONObject(i)
                    val item = q.getString("t") + ":" + q.getString("x")
                    val body = JSONObject().put("v", 1).put("t", q.getString("t")).put("x", q.getString("x"))
                        .put("r", q.getString("r")).put("i", id).put("p", work(item, id)).toString()
                    val c = (URL(REPORT_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    }
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    c.disconnect()
                    if (code !in 200..299) break
                    sent++
                }
                // Neodeslaná zůstanou ve frontě na příště (bez internetu, výpadek)
                val rest = JSONArray()
                val fresh = JSONArray(p.getString("fronta", null) ?: "[]")
                for (i in sent until fresh.length()) rest.put(fresh.get(i))
                p.edit().putString("fronta", rest.toString()).apply()
                Log.i("DruhyNazor", "Do společného seznamu nahlášeno: $sent")
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Hlášení do společného seznamu se nepodařilo odeslat", e)
            } finally {
                sending.set(false)
            }
        }
    }

    // ---------- Stahování seznamu ----------

    private fun get(url: String, timeout: Int = 20_000): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout * 3
            setRequestProperty("Cache-Control", "no-cache")
        }
        val ok = c.responseCode == 200
        val data = if (ok) c.inputStream.use { it.readBytes() } else null
        c.disconnect()
        data
    } catch (e: Exception) { null }

    private fun save(ctx: Context, name: String, data: ByteArray) {
        val f = File(dir(ctx), name)
        val tmp = File(dir(ctx), "$name.novy")
        tmp.writeBytes(data)
        tmp.renameTo(f)
    }

    /** Stáhne nový seznam, když se na serveru změnil. Velký světový seznam (asi 2 MB) jen přes Wi-Fi, nebo jednou za týden. */
    fun refresh(ctx: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        thread(name = "seznam-stazeni") {
            var ok = false
            try {
                val p = prefs(app)
                val infoRaw = get(LIST_URL + "info.json")
                if (infoRaw != null) {
                    val info = JSONObject(String(infoRaw, Charsets.UTF_8))
                    val updated = info.optString("verze")
                    if (force || updated != p.getString("aktualizovano", null)) {
                        val nums = get(LIST_URL + "cisla.txt")
                        val webData = get(LIST_URL + "weby.txt")
                        if (nums != null && webData != null) {
                            save(app, "cisla.txt", nums); save(app, "weby.txt", webData)
                            numbers = null; webs = null
                            p.edit().putString("aktualizovano", updated).putString("info", info.toString()).apply()
                        }
                    }
                    val phishVer = info.optString("phish_verze")
                    val haveVer = p.getString("phish_verze", null)
                    val wifi = !(app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true)
                    val old = System.currentTimeMillis() - p.getLong("phish_cas", 0L) > 7 * DAY
                    if (phishVer.isNotEmpty() && phishVer != haveVer && (wifi || old || force)) {
                        get(LIST_URL + "phish.bin", 30_000)?.takeIf { it.size % 5 == 0 }?.let {
                            save(app, "phish.bin", it)
                            phish = null
                            p.edit().putString("phish_verze", phishVer).putLong("phish_cas", System.currentTimeMillis()).apply()
                        }
                    }
                    p.edit().putLong("stazeno", System.currentTimeMillis()).apply()
                    ok = true
                }
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Společný seznam se nepodařilo stáhnout", e)
            }
            if (onDone != null) Handler(Looper.getMainLooper()).post { onDone(ok) }
        }
    }

    @Volatile private var running = false

    /** Dokud běží hlídání: každou hodinu se podívat na nový seznam a odeslat čekající hlášení. */
    fun startUpdates(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread(name = "seznam", isDaemon = true) {
            while (running) {
                refresh(app)
                sendQueue(app)
                try { Thread.sleep(HOUR) } catch (_: InterruptedException) { }
            }
        }
    }

    fun stopUpdates() { running = false }

    /** Jedna věta pro rodinu do nastavení: co telefon ze společného seznamu zná. */
    fun status(ctx: Context): String {
        val p = prefs(ctx)
        val info = p.getString("info", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            ?: return "Společný seznam podvodů se zatím nestáhl. Stáhne se sám, až bude internet."
        val nums = loadNumbers(ctx).size
        val list = loadWebs(ctx)
        val community = list.values.count { it == Source.COMMUNITY }
        val coi = list.values.count { it == Source.COI }
        val ph = loadPhish(ctx).size / 5
        val own = familyNumbers(ctx).size + familyWebs(ctx).size
        val waiting = JSONArray(p.getString("fronta", null) ?: "[]").length()
        val time = java.text.SimpleDateFormat("d. M. HH:mm", java.util.Locale("cs"))
            .format(java.util.Date(p.getLong("stazeno", 0L)))
        return "Společný seznam (staženo $time): $nums čísel a $community stránek od rodin, " +
            "$coi rizikových e-shopů podle ČOI, $ph podvodných stránek ze světa. " +
            "Vaše rodina označila $own." + (if (waiting > 0) " Čeká na odeslání: $waiting." else "") +
            (info.optString("pozn").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
    }
}
