package cz.druhynazor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Rádio přímo v Postaráno: jedno ťuknutí a hraje, nic se neinstaluje.
 *
 * Český rozhlas to dovoluje ve „Veřejných podmínkách redistribuce online obsahu“ (účinné od 1. 6. 2021,
 * informace.rozhlas.cz, ověřeno 28. 11. 2026): živé vysílání jen „na základě přímého odkazování na zdrojové
 * URL adresy streamů“, bez vlastního restreamingu, bez nahrávání a zpětného poslechu, bez úprav obsahu,
 * Český rozhlas uvést jako výrobce, aplikace se má serverům ČRo hlásit vlastním názvem (User-Agent).
 * To vše tady platí: hrajeme přímo z rozhlas.stream, nic neukládáme, na obrazovce je „Český rozhlas“.
 */
object Radio {
    const val ID = "ra"
    private const val PREFS = "radio"
    fun userAgent(ctx: Context): String {
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        return "Postarano/$v (Android; aplikace pro seniory)"
    }

    data class Station(val id: String, val name: String, val about: String)

    /** Adresy podle rozhlas.stream (128 kb/s MP3, záložně AAC). */
    val STATIONS = listOf(
        Station("dvojka", "Dvojka", "zábava, písničky, povídání"),
        Station("radiozurnal", "Radiožurnál", "zprávy a sport"),
        Station("pohoda", "Pohoda", "staré dobré písničky"),
        Station("vltava", "Vltava", "vážná hudba a četba"),
        Station("plus", "Plus", "rozhovory a publicistika"),
    )

    fun urls(s: Station) = listOf(
        "https://rozhlas.stream/${s.id}.mp3",
        "https://rozhlas.stream/${s.id}_high.aac",
        "https://rozhlas.stream/${s.id}_mp3_128.mp3",
    )

    fun enabled(ctx: Context) = Care.enabled(ctx, ID)

    fun last(ctx: Context): Station {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("stanice", "dvojka")
        return STATIONS.firstOrNull { it.id == id } ?: STATIONS.first()
    }

    private fun setLast(ctx: Context, s: Station) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("stanice", s.id).apply()

    // ---------- Stav přehrávání (čte obrazovka) ----------

    enum class State { OFF, LOADING, PLAYING, ERROR }
    @Volatile var state = State.OFF; private set
    @Volatile var current: Station? = null; private set
    var listener: (() -> Unit)? = null

    internal fun set(st: State, s: Station? = current) {
        state = st; current = s
        Handler(Looper.getMainLooper()).post { listener?.invoke() }
    }

    val playing get() = state == State.PLAYING || state == State.LOADING

    fun play(ctx: Context, s: Station) {
        setLast(ctx, s)
        set(State.LOADING, s)
        val i = Intent(ctx, RadioService::class.java).setAction(RadioService.PLAY).putExtra("id", s.id)
        ctx.startForegroundService(i)
    }

    fun stop(ctx: Context) {
        if (state == State.OFF) return
        ctx.startService(Intent(ctx, RadioService::class.java).setAction(RadioService.STOP))
    }

    /** Dlaždice na úvodní obrazovce: otevře rádio a rovnou pustí naposledy poslouchanou stanici. */
    fun open(a: Activity) {
        a.startActivity(Intent(a, RadioActivity::class.java).putExtra(RadioActivity.EXTRA_AUTOPLAY, true))
    }
}

/** Hraje i se zhasnutým displejem. Při telefonátu ztichne a pak pokračuje. */
class RadioService : Service() {

    private var player: MediaPlayer? = null
    private var station: Radio.Station? = null
    private var urlIndex = 0
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var pausedByCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                val s = Radio.STATIONS.firstOrNull { it.id == intent.getStringExtra("id") } ?: Radio.STATIONS.first()
                foreground(s)
                start(s)
            }
            STOP -> stopAll()
            else -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun foreground(s: Radio.Station) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rádio", NotificationManager.IMPORTANCE_LOW))
        val tap = Notify.pending(this, Intent(this, RadioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), 6262)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("📻 Hraje rádio: ${s.name}")
            .setContentText("Český rozhlas. Ťukněte sem, když ho chcete vypnout.")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF, n)
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Rádio: nejde spustit", e)
        }
    }

    private fun start(s: Radio.Station) {
        release()
        station = s
        urlIndex = 0
        if (!takeFocus()) { Radio.set(Radio.State.ERROR, s); stopAll(keepState = true); return }
        open()
    }

    private fun open() {
        val s = station ?: return
        val list = Radio.urls(s)
        if (urlIndex >= list.size) { Radio.set(Radio.State.ERROR, s); stopAll(keepState = true); return }
        val url = list[urlIndex]
        Radio.set(Radio.State.LOADING, s)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(this, Uri.parse(url), mapOf("User-Agent" to Radio.userAgent(this)))
            mp.setOnPreparedListener { if (player === it) { it.start(); Radio.set(Radio.State.PLAYING, s) } }
            mp.setOnErrorListener { p, what, extra ->
                Log.w("DruhyNazor", "Rádio: $url nejde ($what/$extra)")
                if (player === p) { p.release(); player = null; urlIndex++; open() }
                true
            }
            mp.setOnCompletionListener { p -> if (player === p) { p.release(); player = null; open() } }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Rádio: $url", e)
            mp.release(); player = null; urlIndex++; open()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopAll()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.let { if (it.isPlaying) { it.pause(); pausedByCall = true } }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByCall) {
                pausedByCall = false
                // Živé vysílání po pauze raději načíst znovu, ať nehraje zpožděně
                station?.let { urlIndex = 0; release(); open() }
            }
        }
    }

    private fun takeFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focus = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun release() {
        player?.let { try { it.release() } catch (_: Exception) { } }
        player = null
    }

    private fun stopAll(keepState: Boolean = false) {
        release()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        if (!keepState) Radio.set(Radio.State.OFF, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        release()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (Radio.state != Radio.State.ERROR) Radio.set(Radio.State.OFF, null)
        super.onDestroy()
    }

    companion object {
        const val PLAY = "hrat"
        const val STOP = "vypnout"
        private const val CHANNEL = "radio"
        private const val NOTIF = 62
    }
}
