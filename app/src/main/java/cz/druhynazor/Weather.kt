package cz.druhynazor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Předpověď na jeden den. */
data class WeatherDay(val code: Int, val max: Double, val min: Double, val feelsMax: Double, val rain: Double, val gusts: Double)

/** Varování: 1 = pozor, 2 = velké nebezpečí (rodina dostane SMS). */
data class WeatherWarning(val level: Int, val text: String, val family: String)

/**
 * Počasí a výstrahy. Ráno v 8:00 se telefon podívá na předpověď (Open-Meteo, zdarma pro nekomerční aplikace,
 * licence CC BY 4.0). Když hrozí vedro, náledí, silný vítr nebo bouřka, řekne to nahlas jednou větou, co dělat.
 * Při velkém vedru (od 34 °C) nebo mrznoucím dešti pošle jednu SMS rodině.
 *
 * Hranice pro vedro jsou stejné jako výstrahy ČHMÚ: 31 °C, 34 °C, 37 °C (ČT24, 13. 8. 2024).
 * Ostatní hranice (vítr, mráz) jsou naše, nejsou to oficiální výstrahy ČHMÚ.
 */
object Weather {
    const val ID = "po_ca"
    private const val NOTIF = 61
    private const val HOUR = 8

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pocasi", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = Care.enabled(ctx, ID)
    fun familySms(ctx: Context) = prefs(ctx).getBoolean("sms", true)
    fun setFamilySms(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("sms", on).apply()

    // ---------- Místo ----------

    fun placeName(ctx: Context): String = prefs(ctx).getString("misto", "") ?: ""

    /** Místo, pro které hlásíme počasí. Zaokrouhleno na 2 desetinná místa (asi 1 km), nic přesnějšího se neposílá. */
    fun place(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (p.contains("lat")) return p.getFloat("lat", 0f).toDouble() to p.getFloat("lon", 0f).toDouble()
        // Rodina místo nezadala: vzít poslední známou polohu telefonu, když ji smí zjistit (kvůli Pomoci)
        if (!Help.locationAllowed(ctx)) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
            @Suppress("MissingPermission")
            val l = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: return null
            round(l.latitude) to round(l.longitude)
        } catch (e: SecurityException) { null }
    }

    private fun round(v: Double) = (v * 100).roundToInt() / 100.0

    fun setPlace(ctx: Context, name: String, lat: Double, lon: Double) {
        prefs(ctx).edit().putString("misto", name).putFloat("lat", round(lat).toFloat()).putFloat("lon", round(lon).toFloat())
            .remove("predpoved").apply()
    }

    fun clearPlace(ctx: Context) = prefs(ctx).edit().remove("misto").remove("lat").remove("lon").remove("predpoved").apply()

    data class Found(val name: String, val detail: String, val lat: Double, val lon: Double)

    /** Najít obec podle jména (Open-Meteo, data GeoNames). Běží mimo hlavní vlákno. */
    fun search(name: String): List<Found> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=5&language=cs&countryCode=CZ&name=" +
            URLEncoder.encode(name.trim(), "UTF-8")
        val o = get(url) ?: return emptyList()
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            Found(r.getString("name"), listOf(r.optString("admin2"), r.optString("admin1")).filter { s -> s.isNotBlank() }.distinct().joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    // ---------- Předpověď ----------

    private fun get(url: String): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("user-agent", "Postarano/1.0 (Android)")
        }
        return try {
            if (c.responseCode != 200) { Log.w("DruhyNazor", "Počasí: server ${c.responseCode}"); null }
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) { Log.w("DruhyNazor", "Počasí: nejde stáhnout", e); null } finally { c.disconnect() }
    }

    /** Dnes a zítra. Když není internet, vrátí poslední uloženou předpověď z dneška. Mimo hlavní vlákno. */
    fun forecast(ctx: Context): List<WeatherDay>? {
        val (lat, lon) = place(ctx) ?: return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,precipitation_sum,wind_gusts_10m_max" +
            "&timezone=Europe%2FPrague&forecast_days=2"
        val p = prefs(ctx)
        val o = get(url)?.also { p.edit().putString("predpoved", it.toString()).putString("den", Quiz.today()).apply() }
            ?: p.getString("predpoved", null)?.takeIf { p.getString("den", "") == Quiz.today() }?.let { JSONObject(it) }
            ?: return null
        return try {
            val d = o.getJSONObject("daily")
            fun arr(k: String, i: Int) = d.optJSONArray(k)?.optDouble(i, 0.0) ?: 0.0
            (0 until (d.optJSONArray("time")?.length() ?: 0)).map { i ->
                WeatherDay(arr("weather_code", i).toInt(), arr("temperature_2m_max", i), arr("temperature_2m_min", i),
                    arr("apparent_temperature_max", i), arr("precipitation_sum", i), arr("wind_gusts_10m_max", i))
            }
        } catch (e: Exception) { null }
    }

    fun sky(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "jasno"
        1 -> "🌤️" to "skoro jasno"
        2 -> "⛅" to "polojasno"
        3 -> "☁️" to "zataženo"
        45, 48 -> "🌫️" to "mlha"
        51, 53, 55 -> "🌦️" to "mrholení"
        56, 57 -> "🧊" to "mrznoucí mrholení"
        61 -> "🌦️" to "slabý déšť"
        63 -> "🌧️" to "déšť"
        65 -> "🌧️" to "silný déšť"
        66, 67 -> "🧊" to "mrznoucí déšť"
        71 -> "🌨️" to "slabé sněžení"
        73 -> "🌨️" to "sněžení"
        75 -> "❄️" to "silné sněžení"
        77 -> "🌨️" to "sněhová zrna"
        80, 81 -> "🌦️" to "přeháňky"
        82 -> "🌧️" to "prudké přeháňky"
        85, 86 -> "🌨️" to "sněhové přeháňky"
        95 -> "⛈️" to "bouřky"
        96, 97, 99 -> "⛈️" to "bouřky s kroupami"
        else -> "🌡️" to ""
    }

    private fun deg(v: Double): String {
        val n = v.roundToInt()
        val a = kotlin.math.abs(n)
        val word = when { a == 1 -> "stupeň"; a in 2..4 -> "stupně"; else -> "stupňů" }
        return "$n $word"
    }

    /** Jedna věta: „Dnes bude polojasno, od 3 do 12 stupňů.“ */
    fun sentence(day: WeatherDay, today: Boolean): String {
        val sky = sky(day.code).second
        val start = if (today) "Dnes bude" else "Zítra bude"
        return "$start ${if (sky.isNotEmpty()) "$sky, " else ""}od ${day.min.roundToInt()} do ${deg(day.max)}."
    }

    /** Co hrozí a co dělat. Nejdůležitější první. */
    fun warnings(day: WeatherDay, ctx: Context): List<WeatherWarning> {
        val prefs = Prefs(ctx)
        val who = prefs.seniorName
        val w = mutableListOf<WeatherWarning>()
        val max = day.max.roundToInt()
        when {
            day.max >= 37 -> w += WeatherWarning(2, "Dnes bude extrémní vedro, až ${deg(day.max)}. Zůstaňte doma, zatáhněte závěsy a pijte vodu každou hodinu, i když nemáte žízeň.",
                "Dnes bude extremni vedro, az $max stupnu. Zavolejte, at $who pije a zustane doma.")
            day.max >= 34 -> w += WeatherWarning(2, "Dnes bude velké vedro, až ${deg(day.max)}. Ven jen ráno, přes poledne zůstaňte doma ve stínu a pijte vodu každou hodinu.",
                "Dnes bude velke vedro, az $max stupnu. Zavolejte, at $who pije a v poledne nechodi ven.")
            day.max >= 31 -> w += WeatherWarning(1, "Dnes bude horko, až ${deg(day.max)}. Pijte hodně vody a v poledne nechoďte na slunce.", "")
        }
        val freezingRain = day.code in listOf(56, 57, 66, 67)
        when {
            freezingRain -> w += WeatherWarning(2, "Venku je mrznoucí déšť a bude hodně klouzat. Dnes radši vůbec nechoďte ven.",
                "Dnes je mrznouci dest a nalezi. Zavolejte, at $who nechodi ven.")
            day.min <= 0.5 && (day.rain > 0.2 || day.code in 71..86) ->
                w += WeatherWarning(1, "Venku může klouzat. Když nemusíte, nechoďte ven. Když ano, vezměte si hůl a pevné boty.", "")
        }
        if (day.min <= -10) w += WeatherWarning(1, "Bude velký mráz, až ${deg(day.min)}. Oblečte se teple a topte.", "")
        if (day.gusts >= 70) w += WeatherWarning(1, "Bude silný vítr. Nechoďte do lesa ani do parku pod stromy.", "")
        if (day.code in 95..99) w += WeatherWarning(1, "Můžou být bouřky. Když zahřmí, zůstaňte doma.", "")
        if (day.code == 75 || day.code == 86) w += WeatherWarning(1, "Bude hodně sněžit. Když nemusíte, zůstaňte doma.", "")
        return w.sortedByDescending { it.level }
    }

    // ---------- Ranní kontrola ----------

    private fun alarm(ctx: Context, flags: Int) = PendingIntent.getBroadcast(ctx, 6161,
        Intent(ctx, WeatherReceiver::class.java).setAction("cz.druhynazor.POCASI"), flags or PendingIntent.FLAG_IMMUTABLE)

    /** Naplánovat ranní kontrolu na 8:00 (nebo zrušit). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        alarm(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        if (!enabled(ctx)) return
        val at = Care.nextAt(System.currentTimeMillis(), HOUR, 0)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx, PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /** Ráno: když něco hrozí, ukázat to nahlas a případně poslat SMS rodině. Mimo hlavní vlákno. */
    fun morning(ctx: Context) {
        val app = ctx.applicationContext
        val today = forecast(app)?.firstOrNull() ?: return
        val warn = warnings(today, app)
        if (warn.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        main.post {
            val i = Intent(app, WeatherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            Notify.show(app, NOTIF, Notify.CH_QUESTION, "${sky(today.code).first} Počasí: pozor", warn.first().text, i)
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h in 8..20) try { app.startActivity(i) } catch (e: Exception) { Log.w("DruhyNazor", "Počasí: obrazovka nejde otevřít", e) }
            // Rodině jen při velkém nebezpečí a nejvýš jednou za den (každá SMS stojí kredit)
            val big = warn.firstOrNull { it.level >= 2 && it.family.isNotEmpty() }
            val p = prefs(app)
            if (big != null && familySms(app) && p.getString("sms_den", "") != Quiz.today() &&
                app.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                p.edit().putString("sms_den", Quiz.today()).apply()
                Family.sms(app, "Postarano: " + big.family)
            }
        }
    }
}

class WeatherReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Weather.schedule(ctx)
        val result = goAsync()
        Thread {
            try { Weather.morning(ctx) } catch (e: Exception) { Log.w("DruhyNazor", "Počasí: ranní kontrola selhala", e) }
            finally { result.finish() }
        }.start()
    }
}
