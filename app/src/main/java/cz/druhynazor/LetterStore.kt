package cz.druhynazor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Uložené dopisy: jen krátké shrnutí a zmenšená fotka první strany (kvůli připomínce a rodině).
 * Celý text dopisu se neukládá. Fotky se mažou po 60 dnech a nezálohují se.
 */
object LetterStore {

    data class Saved(
        val id: String,
        val time: Long,
        val about: String,
        val level: ScamCheck.Level,
        val wantsMoney: Boolean,
        val due: LocalDate?,
        val familyText: String,
        val photo: String?,
        val done: Boolean,
    ) {
        fun todo(today: LocalDate): String = when {
            !wantsMoney || due == null -> "Podívejte se na dopis s rodinou."
            due.isBefore(today) -> "Zaplaťte co nejdřív, termín byl už ${LetterReader.date(due)}."
            due == today -> "Zaplaťte ještě dnes."
            else -> "Zaplaťte do ${LetterReader.date(due)}."
        }
    }

    private const val KEEP_DAYS = 60L
    private const val MAX = 15

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dopisy", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "dopisy").apply { mkdirs() }

    fun all(ctx: Context): List<Saved> = try {
        val arr = JSONArray(prefs(ctx).getString("seznam", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Saved(o.getString("id"), o.getLong("t"), o.getString("about"),
                ScamCheck.Level.valueOf(o.getString("level")), o.getBoolean("money"),
                o.optString("due").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                o.getString("family"), o.optString("photo").takeIf { it.isNotEmpty() }, o.optBoolean("done"))
        }
    } catch (e: Exception) { emptyList() }

    private fun write(ctx: Context, list: List<Saved>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("t", s.time).put("about", s.about).put("level", s.level.name)
                .put("money", s.wantsMoney).put("due", s.due?.toString() ?: "").put("family", s.familyText)
                .put("photo", s.photo ?: "").put("done", s.done))
        }
        prefs(ctx).edit().putString("seznam", arr.toString()).apply()
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, l: LetterReader.Letter, firstPage: Uri?): Saved {
        val p = Prefs(ctx)
        val id = System.currentTimeMillis().toString(36)
        val photo = firstPage?.let { smallPhoto(ctx, it, File(dir(ctx), "$id.jpg")) }
        val s = Saved(id, System.currentTimeMillis(), LetterReader.about(l), l.level, l.wantsMoney, l.due,
            LetterReader.familyText(l, p.seniorName, p.seniorMale), photo, false)
        val now = System.currentTimeMillis()
        val (keep, drop) = (listOf(s) + all(ctx)).partition { now - it.time < KEEP_DAYS * 24 * 3600 * 1000 }
        val kept = keep.take(MAX)
        (drop + keep.drop(MAX)).forEach { old -> old.photo?.let { File(it).delete() } }
        write(ctx, kept)
        return s
    }

    fun done(ctx: Context, id: String) = write(ctx, all(ctx).map { if (it.id == id) it.copy(done = true) else it })

    /**
     * Zmenšená fotka (do 300 kB), aby prošla jako MMS. Původní fotky ze skeneru zůstávají jen
     * v dočasné paměti telefonu, kterou si Android sám uklízí.
     */
    fun smallPhoto(ctx: Context, src: Uri, out: File): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(src)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("fotka se nenačetla")
        var side = 1400
        var quality = 70
        var bytes: ByteArray
        while (true) {
            val scale = minOf(1f, side.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (scaled !== bmp) scaled.recycle()
            if (bytes.size <= 280_000 || side <= 700) break
            if (quality > 50) quality -= 10 else side -= 200
        }
        bmp.recycle()
        out.writeBytes(bytes)
        out.absolutePath
    } catch (e: Exception) {
        Log.w("DruhyNazor", "Fotku dopisu se nepodařilo uložit", e)
        null
    }
}

/**
 * Připomínka termínu: dva dny před termínem v 9 ráno a v den termínu v 9 ráno
 * (dokud senior neťukne na Už je zaplaceno). Ukáže se přes celou obrazovku a řekne se nahlas.
 */
object LetterReminder {
    private const val HOUR = 9

    /** Naplánuje připomínky a vrátí den první z nich. */
    fun schedule(ctx: Context, id: String, due: LocalDate): LocalDate {
        val today = LocalDate.now()
        val first = maxOf(due.minusDays(2), if (LocalDateTime.now().hour < HOUR) today else today.plusDays(1))
        set(ctx, id, first, 0)
        if (due.isAfter(first)) set(ctx, id, due, 1)
        val p = ctx.getSharedPreferences("dopisy", Context.MODE_PRIVATE)
        p.edit().putStringSet("pripominky", (p.getStringSet("pripominky", null) ?: emptySet()) + id).apply()
        return first
    }

    private fun pi(ctx: Context, id: String, n: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(ctx, (id.hashCode() and 0xFFFFFF) * 2 + n,
            Intent(ctx, LetterReminderReceiver::class.java).putExtra("id", id), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun set(ctx: Context, id: String, day: LocalDate, n: Int) {
        val at = day.atTime(HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at < System.currentTimeMillis()) return
        ctx.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, id, n)!!)
    }

    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        for (n in 0..1) pi(ctx, id, n, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        val p = ctx.getSharedPreferences("dopisy", Context.MODE_PRIVATE)
        p.edit().putStringSet("pripominky", (p.getStringSet("pripominky", null) ?: emptySet()) - id).apply()
    }

    /** Po zapnutí telefonu Android budíky zapomene: naplánovat znovu. */
    fun restore(ctx: Context) {
        val ids = ctx.getSharedPreferences("dopisy", Context.MODE_PRIVATE).getStringSet("pripominky", null) ?: return
        for (s in LetterStore.all(ctx)) {
            if (s.id !in ids || s.done || s.due == null) continue
            val today = LocalDate.now()
            val first = s.due.minusDays(2)
            if (first.isAfter(today) || (first == today && LocalDateTime.now().hour < HOUR)) set(ctx, s.id, first, 0)
            set(ctx, s.id, s.due, 1)
        }
    }
}

class LetterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val s = LetterStore.get(ctx, id) ?: return
        if (s.done) return
        val open = Intent(ctx, LetterActivity::class.java).putExtra(LetterActivity.EXTRA_REMIND, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Pro jistotu i oznámení (kdyby telefon nedovolil otevřít obrazovku sám)
        Notify.show(ctx, 100 + (id.hashCode() and 0xFFF), Notify.CH_QUESTION, "Připomínám dopis", "${s.about} ${s.todo(LocalDate.now())}", open)
        try { ctx.startActivity(open) } catch (e: Exception) { Log.w("DruhyNazor", "Připomínku se nepodařilo otevřít", e) }
    }
}
