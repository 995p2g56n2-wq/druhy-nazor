package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.druhynazor.Ui.dp
import java.util.Locale

/**
 * Pomoc: velké červené tlačítko na úvodní obrazovce.
 * Jde otevřít i přímo z plochy (červená ikona Pomoc).
 * Odpočítá 15 vteřin nahlas (kdyby senior zavadil omylem, stačí obrovské Zrušit), pak pošle rodině SMS
 * s polohou a zavolá prvnímu z rodiny. Po hovoru může zavolat dalším nebo záchrance.
 */
class HelpActivity : Activity() {

    private lateinit var speaker: Speaker
    private var left = COUNTDOWN
    private var sent = false
    private var test = false
    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing || sent) return
            left--
            if (left <= 0) { go(); return }
            render()
            // Odpočet nahlas: deset a pak posledních pět vteřin
            if (left == 10 || left <= 5) speaker.say(NUMBERS[left] ?: "$left")
            window.decorView.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speaker = Speaker(this)
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        // Pomoc je v Pro rodinu vypnutá: ikona z plochy nic neudělá
        if (!test && !Care.enabled(this, Care.POMOC)) { finish(); return }
        render()
        val prefs = Prefs(this)
        speaker.say("Za patnáct vteřin zavolám: ${prefs.familyFirstName}, a pošlu, kde jste. Když jste zmáčkli omylem, ťukněte na velké bílé tlačítko Zrušit.")
        window.decorView.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(tick)
        speaker.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val prefs = Prefs(this)
        val root = Ui.column(this, 24, 20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(24)))
        if (!sent) {
            root.addView(Ui.text(this, "🆘", 80f, center = true))
            root.addView(Ui.text(this, "Volám o pomoc", 36f, bold = true, center = true, color = Color.WHITE))
            root.addView(Ui.text(this, "$left", 90f, bold = true, center = true, color = Color.WHITE))
            root.addView(Ui.text(this, "Zavolám: ${prefs.familyFirstName} a pošlu, kde jste.", 24f, center = true, color = Color.WHITE))
            root.addView(Ui.text(this, "Omyl? Ťukněte na Zrušit.", 22f, center = true, color = Color.WHITE))
            root.addView(Ui.button(this, "✋  ZRUŠIT", Color.WHITE, Ui.BAD, sp = 44f) { finish() }.apply { minHeight = dp(220) })
            // Pro spěch: menší tlačítko daleko od Zrušit, aby se nedalo trefit omylem
            root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(60)))
            root.addView(Ui.button(this, "Zavolat hned, nečekat", Ui.BAD, Color.WHITE, sp = 18f) { go() }.apply {
                background = Ui.rounded(Ui.BAD, 18, this@HelpActivity, Color.WHITE)
            })
        } else {
            root.addView(Ui.text(this, "📞", 70f, center = true))
            val line = if (prefs.familyContacts.isEmpty()) "Nikoho z rodiny nemám v seznamu."
                       else "${prefs.familyFirstName} už má zprávu i s tím, kde jste."
            root.addView(Ui.text(this, line, 28f, bold = true, center = true, color = Color.WHITE))
            root.addView(Ui.text(this, "Můžete zavolat znovu, nebo někomu dalšímu.", 21f, center = true, color = Color.WHITE))
            for (c in prefs.familyContacts) {
                root.addView(Ui.button(this, "📞  Zavolat: ${c.name}", Color.WHITE, Ui.INK, sp = 24f) { call(c.phone) })
            }
            root.addView(Ui.button(this, "🚑  Záchranka 155", Color.WHITE, Ui.BAD, sp = 24f) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:155")))
            })
            // Není to nouze, ale potřeba si s někým promluvit: bezplatná Linka seniorů
            root.addView(Ui.button(this, "☎️  Popovídat si: Linka seniorů 800 200 007 (zdarma)", Color.WHITE, Ui.ACCENT, sp = 18f) {
                Linka.call(this)
            })
            root.addView(Ui.button(this, "Zavřít", Ui.BAD, Color.WHITE, sp = 18f) { finish() })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BAD); isFillViewport = true; addView(root) })
    }

    private fun go() {
        if (sent) return
        sent = true
        window.decorView.removeCallbacks(tick)
        speaker.stop()
        val prefs = Prefs(this)
        Help.alert(this, test)
        render()
        prefs.familyContacts.firstOrNull()?.let { call(it.phone) }
    }

    /** Rovnou vytočit. Když telefon nedovolí, otevře se číslo a senior ťukne na zelené sluchátko. */
    private fun call(phone: String) {
        val uri = Uri.parse("tel:" + Family.cleanNumber(phone))
        val direct = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        try {
            startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_DIAL, uri)) } catch (_: Exception) { }
        }
    }

    companion object {
        const val EXTRA_TEST = "zkouska"
        private const val COUNTDOWN = 15
        private val NUMBERS = mapOf(10 to "Deset", 5 to "Pět", 4 to "Čtyři", 3 to "Tři", 2 to "Dvě", 1 to "Jedna")
    }
}

/** Poloha a SMS rodině. Běží i dál, když se během hovoru obrazovka schová. */
object Help {
    private const val FRESH = 3 * 60_000L

    fun locationAllowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun callAllowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun link(l: Location) = "https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, l.latitude, l.longitude)

    private fun where(l: Location): String {
        val acc = if (l.hasAccuracy()) " (přesnost asi ${l.accuracy.toInt()} m)" else ""
        return link(l) + acc
    }

    fun alert(ctx: Context, test: Boolean) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val head = if (test) "Postaráno: ZKOUŠKA tlačítka Pomoc, nic se neděje. " else "Postaráno: POMOC! "
        val who = "${prefs.seniorName} ${Care.g(prefs, "zmáčkla", "zmáčkl")} tlačítko Pomoc a volá vám."
        val lm = app.getSystemService(LocationManager::class.java)
        if (!locationAllowed(app) || lm == null) {
            Family.sms(app, "$head$who Polohu nevím, telefon ji nesmí zjistit.", maxParts = 2)
            return
        }
        val last = lastKnown(app, lm)
        val now = System.currentTimeMillis()
        if (last != null && now - last.time < FRESH) {
            Family.sms(app, "$head$who Je tady: ${where(last)}", maxParts = 2)
            return
        }
        // Stará nebo žádná poloha: SMS hned, přesnou polohu pošleme, jakmile ji telefon najde
        val old = last?.let { " Naposledy ${minutesAgo(now - it.time)} byl telefon tady: ${link(it)}" } ?: ""
        Family.sms(app, "$head$who Polohu ještě hledám, pošlu ji v další SMS.$old", maxParts = 2)
        current(app, lm) { loc ->
            val text = if (loc != null) "Postaráno: ${prefs.seniorName} je teď tady: ${where(loc)}"
                       else "Postaráno: Polohu ${prefs.seniorGenitive} se nepodařilo zjistit" +
                           if (!lm.isLocationEnabled) ", v telefonu je vypnutá poloha." else "."
            Family.sms(app, if (test) "$text (zkouška)" else text, maxParts = 2)
        }
    }

    private fun minutesAgo(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "před $m min"
            m < 48 * 60 -> "před ${m / 60} h"
            else -> "před ${m / (24 * 60)} dny"
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnown(ctx: Context, lm: LocationManager): Location? = try {
        lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) { null }

    /** Najít polohu teď: GPS i síť naráz, vezme se první. Nejvýš 60 vteřin. */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun current(ctx: Context, lm: LocationManager, done: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var answered = false
        fun answer(l: Location?) { main.post { if (!answered) { answered = true; done(l) } } }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (providers.isEmpty()) { answer(null); return }
        val cancel = CancellationSignal()
        var waiting = providers.size
        for (p in providers) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { l ->
                        if (l != null) { cancel.cancel(); answer(l) } else if (--waiting == 0) answer(null)
                    }
                } else {
                    lm.requestSingleUpdate(p, object : android.location.LocationListener {
                        override fun onLocationChanged(l: Location) { answer(l) }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                        override fun onProviderEnabled(provider: String) { }
                        override fun onProviderDisabled(provider: String) { }
                    }, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Poloha z $p nejde", e)
                if (--waiting == 0) answer(null)
            }
        }
        main.postDelayed({ if (!answered) { cancel.cancel(); answer(lastKnown(ctx, lm)?.takeIf { System.currentTimeMillis() - it.time < FRESH }) } }, 60_000)
    }
}
