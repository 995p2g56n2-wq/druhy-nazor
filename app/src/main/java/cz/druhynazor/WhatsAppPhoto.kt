package cz.druhynazor

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Fotka dopisu rodině přes WhatsApp místo placené MMS. Žádná aplikace nesmí poslat zprávu
 * přes WhatsApp sama, takže Postaráno otevře WhatsApp rovnou v chatu s člověkem z rodiny,
 * s fotkou i krátkým textem připraveným. Senior jen ťukne na zelenou šipku.
 */
object WhatsAppPhoto {

    private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Balíček WhatsAppu v telefonu, nebo null, když tam WhatsApp není. */
    fun app(ctx: Context): String? = PACKAGES.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
    }

    /** Telefonní číslo ve tvaru, kterému WhatsApp rozumí (420… bez plusu). České číslo bez předvolby doplní. */
    fun jid(phone: String): String? {
        var d = Family.cleanNumber(phone).filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (d.length == 9) d = "420$d"
        return if (d.length < 11) null else "$d@s.whatsapp.net"
    }

    /**
     * Otevře WhatsApp s fotkou pro daného člověka. Vrací false, když to nešlo
     * (WhatsApp chybí, fotka se nenačetla, číslo je špatně).
     */
    fun open(activity: Activity, photo: Uri, text: String, contact: FamilyContact): Boolean {
        val pkg = app(activity) ?: return false
        return try {
            // Staré fotky pro WhatsApp uklidit (WhatsApp si je mezitím zkopíroval)
            Mms.dir(activity).listFiles { f -> f.name.startsWith("wa-") && f.lastModified() < System.currentTimeMillis() - 86_400_000 }
                ?.forEach { it.delete() }
            val name = "wa-${System.currentTimeMillis().toString(36)}.jpg"
            val out = File(Mms.dir(activity), name)
            val src = photo.path
            if (photo.scheme == "file" && src != null && File(src).length() in 1..1_500_000) File(src).copyTo(out, overwrite = true)
            else LetterStore.smallPhoto(activity, photo, out) ?: return false
            val uri = Uri.parse("content://${activity.packageName}.mms/$name")
            val i = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("dopis", uri)
            jid(contact.phone)?.let { i.putExtra("jid", it) }
            activity.startActivity(i)
            true
        } catch (e: Exception) {
            Log.w("DruhyNazor", "WhatsApp s fotkou dopisu se nepodařilo otevřít", e)
            false
        }
    }
}
