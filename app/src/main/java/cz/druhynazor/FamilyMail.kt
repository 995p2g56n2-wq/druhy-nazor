package cz.druhynazor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Calendar

/** Jedna věc od rodiny: fotka, hlasový nebo psaný vzkaz. */
data class MailItem(
    val id: String, val from: String, val kind: String, val caption: String, val mime: String,
    val time: Long, val seen: Boolean, val heart: Boolean,
) {
    val isPhoto get() = kind == "foto"
    val isVoice get() = kind == "hlas"
    val isVideo get() = kind == "video"
}

/**
 * Od rodiny: fotky a vzkazy od rodiny.
 *
 * Každý z rodiny dostane v SMS vlastní odkaz na jednoduchou stránku (server/dopisy/src/rodina.ts),
 * kde vyfotí nebo vybere fotku, nahraje hlas nebo napíše vzkaz. Telefon seniora se každých 15 minut
 * zeptá serveru, jestli je něco nového, stáhne to a ukáže přes celou obrazovku.
 * Senior odpoví srdíčkem (rodina ho uvidí na stránce) nebo zavolá. Nic se neplatí za SMS kromě
 * jedné SMS s odkazem pro každého z rodiny.
 */
object FamilyMail {
    const val ID = "rd"
    private const val APP_KEY = "postarano-dopisy-v1"
    private const val DEFAULT_SERVER = "https://postarano-dopisy.fg95f6rkp6.workers.dev"
    private const val KEEP = 30L * 24 * 3600 * 1000
    private const val NOTIF = 60

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rodina_posta", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = Care.enabled(ctx, ID)

    fun server(ctx: Context) = AiLetters.server(ctx).ifEmpty { DEFAULT_SERVER }

    private fun random(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Schránka a klíč ke čtení vzniknou jednou, v telefonu seniora. */
    private fun box(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        var box = p.getString("schranka", null)
        var read = p.getString("cteni", null)
        if (box == null || read == null) {
            box = random(12); read = random(18)
            p.edit().putString("schranka", box).putString("cteni", read).apply()
        }
        return box to read
    }

    /** Klíč každého člověka z rodiny (podle čísla). */
    private fun memberKey(ctx: Context, phone: String): String {
        val p = prefs(ctx)
        val keys = JSONObject(p.getString("clenove", "{}") ?: "{}")
        val n = Prefs.normalize(phone)
        if (!keys.has(n)) { keys.put(n, random(12)); p.edit().putString("clenove", keys.toString()).apply() }
        return keys.getString(n)
    }

    fun link(ctx: Context, c: FamilyContact): String = "${server(ctx)}/r/${box(ctx).first}/${memberKey(ctx, c.phone)}"

    fun linkSent(ctx: Context, c: FamilyContact) = prefs(ctx).getBoolean("poslano_" + Prefs.normalize(c.phone), false)

    /** Kdy se naposledy podařilo spojit se serverem (0 = nikdy). */
    fun lastOk(ctx: Context) = prefs(ctx).getLong("spojeno", 0L)

    // ---------- Seznam toho, co přišlo ----------

    fun items(ctx: Context): List<MailItem> = try {
        val arr = JSONArray(prefs(ctx).getString("polozky", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MailItem(o.getString("id"), o.getString("od"), o.getString("druh"), o.optString("popis"), o.optString("mime"),
                o.getLong("cas"), o.optBoolean("v"), o.optBoolean("s"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<MailItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("od", it.from).put("druh", it.kind).put("popis", it.caption)
                .put("mime", it.mime).put("cas", it.time).put("v", it.seen).put("s", it.heart))
        }
        prefs(ctx).edit().putString("polozky", arr.toString()).apply()
    }

    fun unseen(ctx: Context) = if (enabled(ctx)) items(ctx).count { !it.seen } else 0

    fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "rodina"), id.filter { it.isLetterOrDigit() || it == '-' || it == '_' })

    // ---------- Server ----------

    private fun call(ctx: Context, method: String, path: String, body: String? = null): HttpURLConnection {
        val (box, read) = box(ctx)
        return (URL(server(ctx) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("x-postarano", APP_KEY)
            setRequestProperty("x-schranka", box)
            setRequestProperty("x-cteni", read)
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
    }

    private fun json(c: HttpURLConnection): JSONObject? = try {
        if (c.responseCode !in 200..299) { Log.w("DruhyNazor", "Od rodiny: server ${c.responseCode}"); null }
        else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } catch (e: Exception) { Log.w("DruhyNazor", "Od rodiny: odpověď nejde přečíst", e); null } finally { c.disconnect() }

    /** Zapsat rodinu na server, když se změnila (nebo ještě nikdy). */
    private fun register(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val members = JSONArray()
        prefs.familyContacts.forEach { members.put(JSONObject().put("klic", memberKey(ctx, it.phone)).put("jmeno", it.name)) }
        val body = JSONObject().put("senior", prefs.seniorName).put("clenove", members).toString()
        val p = prefs(ctx)
        if (p.getString("zapsano", "") == body) return true
        val ok = json(call(ctx, "POST", "/rodina/nastavit", body))?.optBoolean("ok") == true
        if (ok) p.edit().putString("zapsano", body).apply()
        return ok
    }

    /**
     * Zeptat se serveru na novinky a stáhnout je. Běží mimo hlavní vlákno.
     * Vrací počet nových věcí, nebo -1, když se nepodařilo spojit.
     */
    @Synchronized
    fun sync(ctx: Context): Int {
        if (!enabled(ctx) || Prefs(ctx).familyContacts.isEmpty()) return 0
        return try {
            if (!register(ctx)) return -1
            sendLinks(ctx)
            val p = prefs(ctx)
            val after = p.getLong("posledni", 0L)
            val res = json(call(ctx, "GET", "/rodina/nove?po=$after")) ?: return -1
            val arr = res.optJSONArray("polozky") ?: JSONArray()
            val list = items(ctx).toMutableList()
            var last = after
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val kind = o.getString("druh")
                if (list.any { it.id == id }) { last = maxOf(last, o.getLong("seq")); continue }
                if (kind == "foto" || kind == "hlas" || kind == "video") {
                    val c = call(ctx, "GET", "/rodina/soubor?id=$id")
                    try {
                        if (c.responseCode != 200) { Log.w("DruhyNazor", "Od rodiny: soubor $id ${c.responseCode}"); break }
                        val f = file(ctx, id)
                        f.parentFile?.mkdirs()
                        // Nejdřív do dočasného souboru, ať po přerušeném stahování nezůstane půlka videa
                        val tmp = java.io.File(f.path + ".part")
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        if (!tmp.renameTo(f)) { tmp.delete(); break }
                    } finally { c.disconnect() }
                }
                list += MailItem(id, o.getString("od"), kind, o.optString("popis"), o.optString("mime"), o.getLong("cas"), false, false)
                last = maxOf(last, o.getLong("seq"))
                added++
            }
            // Staré věci smazat i v telefonu (na serveru zmizí samy)
            val now = System.currentTimeMillis()
            val (keep, old) = list.partition { now - it.time < KEEP }
            old.forEach { file(ctx, it.id).delete() }
            save(ctx, keep)
            p.edit().putLong("posledni", last).putLong("spojeno", now).apply()
            added
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Od rodiny: spojení se nepodařilo", e)
            -1
        }
    }

    /** Každý z rodiny dostane svůj odkaz jednou v SMS. Až potom, co je zapsaný na serveru. */
    private fun sendLinks(ctx: Context) {
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = Prefs(ctx)
        for (c in prefs.familyContacts) {
            if (linkSent(ctx, c)) continue
            prefs(ctx).edit().putBoolean("poslano_" + Prefs.normalize(c.phone), true).apply()
            Family.sendSms(ctx, c.phone, smsText(ctx, c))
        }
    }

    fun smsText(ctx: Context, c: FamilyContact): String {
        // Bez háčků se to vejde do jedné SMS i s odkazem (SmsText.clean je stejně odstraní)
        val l = link(ctx, c)
        val t = SmsText.clean("Postarano: fotky a videa na telefon ${Prefs(ctx).seniorGenitive} posilejte sem, ulozte si: $l")
        // Dlouhé jméno by odkaz na konci uřízlo, pak raději bez jména
        return if (t.length <= SmsText.limit(1)) t else "Postarano: fotky a videa posilejte sem, ulozte si: $l"
    }

    /** Poslat odkaz tomuto člověku znovu (Pro rodinu). */
    fun resend(ctx: Context, c: FamilyContact, done: (Boolean) -> Unit) {
        Thread {
            val ok = try { register(ctx) } catch (e: Exception) { false }
            Handler(Looper.getMainLooper()).post {
                if (!ok) { done(false); return@post }
                prefs(ctx).edit().putBoolean("poslano_" + Prefs.normalize(c.phone), true).apply()
                Family.sendSms(ctx, c.phone, smsText(ctx, c), quick = true) { done(it) }
            }
        }.start()
    }

    /** Senior to viděl (a možná poslal srdíčko). Rodina to uvidí na své stránce. */
    fun markSeen(ctx: Context, id: String, heart: Boolean = false) {
        val list = items(ctx)
        val item = list.firstOrNull { it.id == id } ?: return
        if (item.seen && (!heart || item.heart)) return
        save(ctx, list.map { if (it.id == id) it.copy(seen = true, heart = it.heart || heart) else it })
        val app = ctx.applicationContext
        Thread {
            try { json(call(app, "POST", "/rodina/videno", JSONObject().put("id", id).put("srdce", heart).toString())) }
            catch (e: Exception) { Log.w("DruhyNazor", "Od rodiny: viděno se neodeslalo", e) }
        }.start()
    }

    // ---------- Pravidelná kontrola ----------

    private fun alarm(ctx: Context, flags: Int) = PendingIntent.getBroadcast(ctx, 6060,
        Intent(ctx, FamilyMailReceiver::class.java).setAction("cz.druhynazor.RODINA"), flags or PendingIntent.FLAG_IMMUTABLE)

    /** Naplánovat kontrolu každých 15 minut (nebo zrušit, když je Od rodiny vypnuté). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!enabled(ctx) || Prefs(ctx).familyContacts.isEmpty()) {
            alarm(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            return
        }
        if (alarm(ctx, PendingIntent.FLAG_NO_CREATE) != null) return
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES, alarm(ctx, PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /** Zkontrolovat hned (otevření aplikace). Nové věci ukáže. */
    fun checkNow(ctx: Context, done: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val n = sync(app)
            Handler(Looper.getMainLooper()).post { done?.invoke(n) }
        }.start()
    }

    /** Přišlo něco nového: oznámení, a přes den rovnou obrazovka přes celý displej. */
    fun announce(ctx: Context) {
        val list = items(ctx).filter { !it.seen }
        if (list.isEmpty()) return
        val first = list.first()
        val what = when {
            list.size > 1 -> "Přišlo ${list.size} nových věcí od rodiny"
            first.isPhoto -> "Nová fotka: posílá ${first.from}"
            first.isVoice -> "Nový hlasový vzkaz: posílá ${first.from}"
            first.isVideo -> "Nové video: posílá ${first.from}"
            else -> "Nový vzkaz: posílá ${first.from}"
        }
        val i = Intent(ctx, FamilyMailActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Notify.show(ctx, NOTIF, Notify.CH_QUESTION, "📷 Od rodiny", "$what. Ťukněte sem.", i)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h in 8..20) try { ctx.startActivity(i) } catch (e: Exception) { Log.w("DruhyNazor", "Od rodiny: obrazovka nejde otevřít", e) }
    }

    fun clearNotification(ctx: Context) {
        try { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF) } catch (_: Exception) { }
    }
}

class FamilyMailReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        val app = ctx.applicationContext
        Thread {
            try {
                if (FamilyMail.sync(app) > 0) Handler(Looper.getMainLooper()).post { FamilyMail.announce(app) }
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Od rodiny: kontrola selhala", e)
            } finally { result.finish() }
        }.start()
    }
}
