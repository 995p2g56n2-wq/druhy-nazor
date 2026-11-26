package cz.druhynazor

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.telephony.SmsManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Fotka dopisu rodině jako obyčejná MMS. Rodina nemusí nic instalovat, fotka přijde do Zpráv.
 * Postaráno je hlavní aplikace na zprávy, takže smí MMS posílat sama. Telefon k tomu potřebuje
 * mobilní data (MMS nejdou přes Wi-Fi). Když MMS neprojde, rodina má aspoň SMS se shrnutím.
 */
object Mms {

    fun sendPhoto(ctx: Context, photo: Uri, text: String) {
        val prefs = Prefs(ctx)
        val jpeg = try {
            if (photo.scheme == "file" && File(photo.path!!).length() <= 290_000) File(photo.path!!).readBytes()
            else {
                val tmp = File(ctx.cacheDir, "mms-foto.jpg")
                LetterStore.smallPhoto(ctx, photo, tmp)?.let { File(it).readBytes() } ?: return
            }
        } catch (e: Exception) { Log.w("DruhyNazor", "Fotku pro MMS se nepodařilo načíst", e); return }
        for ((i, c) in prefs.familyContacts.withIndex()) {
            try {
                val pdu = encode(c.phone, "Dopis", text, jpeg)
                val name = "mms-${System.currentTimeMillis()}-$i.pdu"
                File(dir(ctx), name).writeBytes(pdu)
                val uri = Uri.parse("content://${ctx.packageName}.mms/$name")
                val sent = PendingIntent.getBroadcast(ctx, 7000 + i,
                    Intent(ctx, MmsSentReceiver::class.java).putExtra("soubor", name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val sm = Family.smsManager(ctx)
                sm.sendMultimediaMessage(ctx, uri, null, null, sent)
                Log.i("DruhyNazor", "MMS s fotkou dopisu odeslána ke zpracování")
            } catch (e: Exception) {
                Log.w("DruhyNazor", "MMS s fotkou dopisu se nepodařilo odeslat", e)
            }
        }
    }

    fun dir(ctx: Context) = File(ctx.applicationContext.cacheDir, "mms").apply { mkdirs() }

    // ---------- Sestavení MMS (OMA MMS Encapsulation, m-send-req) ----------

    private fun ByteArrayOutputStream.uintvar(v: Long) {
        var n = v
        val bytes = mutableListOf((n and 0x7F).toInt())
        n = n shr 7
        while (n > 0) { bytes.add(0, ((n and 0x7F) or 0x80).toInt()); n = n shr 7 }
        bytes.forEach { write(it) }
    }

    private fun ByteArrayOutputStream.textString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) write(0x7F)
        write(b); write(0)
    }

    private fun ByteArrayOutputStream.valueLength(n: Int) {
        if (n < 31) write(n) else { write(31); uintvar(n.toLong()) }
    }

    /** Text v UTF-8 (předmět): délka, znaková sada, text. */
    private fun ByteArrayOutputStream.utf8String(s: String) {
        val inner = ByteArrayOutputStream().apply { write(0xEA); textString(s) }.toByteArray()
        valueLength(inner.size); write(inner)
    }

    fun encode(to: String, subject: String, text: String, jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x80)                          // X-Mms-Message-Type: m-send-req
        out.write(0x98); out.textString("P" + System.currentTimeMillis().toString(36)) // Transaction-ID
        out.write(0x8D); out.write(0x92)                          // MMS verze 1.2
        out.write(0x89); out.write(0x01); out.write(0x81)          // From: doplní operátor
        val number = to.filter { it.isDigit() || it == '+' }
        out.write(0x97); out.textString("$number/TYPE=PLMN")       // To
        out.write(0x96); out.utf8String(subject)                   // Subject
        out.write(0x8A); out.write(0x80)                          // Message-Class: personal
        out.write(0x86); out.write(0x81)                          // Delivery-Report: ne
        out.write(0x90); out.write(0x81)                          // Read-Report: ne
        out.write(0x84); out.write(0xA3)                          // Content-Type: multipart/mixed

        val parts = listOf(
            // text/plain; charset=utf-8
            byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte()) to ("text.txt" to text.toByteArray(Charsets.UTF_8)),
            // image/jpeg
            byteArrayOf(0x9E.toByte()) to ("dopis.jpg" to jpeg),
        )
        out.uintvar(parts.size.toLong())
        for ((type, loc) in parts) {
            val headers = ByteArrayOutputStream().apply {
                write(type)
                write(0x8E); textString(loc.first)                   // Content-Location
            }.toByteArray()
            out.uintvar(headers.size.toLong())
            out.uintvar(loc.second.size.toLong())
            out.write(headers)
            out.write(loc.second)
        }
        return out.toByteArray()
    }
}

/** Po odeslání MMS smaže dočasný soubor. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("DruhyNazor", "MMS s fotkou dopisu: výsledek $resultCode (${if (resultCode == Activity.RESULT_OK) "odešla" else "neodešla"})")
        intent.getStringExtra("soubor")?.let { File(Mms.dir(ctx), it).delete() }
    }
}

/** Zpřístupní připravenou MMS telefonu, aby ji mohl odeslat. Nic jiného nezpřístupní. */
class MmsFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!name.matches(Regex("mms-[0-9a-z-]+\\.pdu"))) return null
        val f = File(Mms.dir(ctx), name)
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri) = "application/vnd.wap.mms-message"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
