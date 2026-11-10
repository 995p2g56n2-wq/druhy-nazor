package cz.druhynazor

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

data class Sms(val id: Long, val address: String, val body: String, val date: Long, val incoming: Boolean)

/** Ukládání a čtení SMS. Výchozí aplikace na zprávy si zprávy ukládá sama. */
object SmsStore {

    fun saveIncoming(context: Context, address: String, body: String, sentAt: Long): Long? = try {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull()
    } catch (e: Exception) {
        Log.w("DruhyNazor", "SMS se nepodařilo uložit", e); null
    }

    fun send(context: Context, address: String, body: String) {
        val sms = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        val parts = sms.divideMessage(body)
        sms.sendMultipartTextMessage(address, null, parts, null, null)
        try {
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, v)
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Odeslanou SMS se nepodařilo uložit", e)
        }
    }

    fun latest(context: Context, limit: Int = 60): List<Sms> {
        val out = mutableListOf<Sms>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DEFAULT_SORT_ORDER
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += Sms(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        incoming = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("DruhyNazor", "SMS se nepodařilo načíst", e)
        }
        return out
    }

    fun byId(context: Context, id: Long): Sms? = try {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms._ID} = ?", arrayOf(id.toString()), null
        )?.use { c ->
            if (c.moveToFirst()) Sms(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3),
                c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX) else null
        }
    } catch (e: Exception) { null }

    fun markRead(context: Context, id: Long) {
        try {
            val v = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, v, "${Telephony.Sms._ID} = ?", arrayOf(id.toString()))
        } catch (_: Exception) { }
    }
}
