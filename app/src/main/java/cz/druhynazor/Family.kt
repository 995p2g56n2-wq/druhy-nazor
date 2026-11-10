package cz.druhynazor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Upozornění rodině přes bezplatnou službu ntfy.sh.
 * Člen rodiny si do svého telefonu nainstaluje aplikaci „ntfy“ a přihlásí se k odběru kanálu z nastavení.
 */
object Family {
    fun notify(context: Context, title: String, message: String, urgent: Boolean = true) {
        val channel = Prefs(context).familyChannel
        thread(name = "family-notify") {
            try {
                val body = JSONObject()
                    .put("topic", channel)
                    .put("title", title)
                    .put("message", message)
                    .put("priority", if (urgent) 5 else 3)
                    .put("tags", org.json.JSONArray().put(if (urgent) "rotating_light" else "warning"))
                    .toString()
                val conn = (URL("https://ntfy.sh/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Log.i("DruhyNazor", "Upozornění rodině odesláno, odpověď $code")
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Upozornění rodině se nepodařilo odeslat", e)
            }
        }
    }
}
