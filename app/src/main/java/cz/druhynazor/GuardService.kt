package cz.druhynazor

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Běží na pozadí a sleduje, kdy hovor začne a skončí.
 * Hovor s neznámým číslem: po X minutách zpráva rodině, po zavěšení otázka ANO / NE.
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var inUnknownCall = false
    private var unknownNumber = ""
    private var callStart = 0L
    private var telephony: TelephonyManager? = null
    private var callback: Any? = null

    private val longCall = Runnable {
        val prefs = Prefs(this)
        val minutes = ((System.currentTimeMillis() - callStart) / 60_000).coerceAtLeast(1)
        Family.notify(this, "${prefs.seniorName} mluví $minutes min s neznámým číslem",
            "Číslo: ${unknownNumber.ifBlank { "skryté" }}. Až zavěsí, zkuste jí zavolat.", urgent = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.setup(this)
        val tap = Notify.pending(this, Intent(this, MainActivity::class.java), 0)
        val n = Notification.Builder(this, Notify.CH_GUARD)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Druhý názor hlídá")
            .setContentText("Ochrana před podvody je zapnutá.")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.ID_GUARD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notify.ID_GUARD, n)
            }
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Hlídání se nepodařilo spustit", e)
            stopSelf()
            return
        }
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DruhyNazor", "Chybí oprávnění ke stavu telefonu")
            return
        }
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        telephony = tm
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            callback = l
        }
    }

    private fun onState(state: Int) {
        val prefs = Prefs(this)
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val recentUnknown = System.currentTimeMillis() - prefs.lastUnknownTime < 120_000
                if (!inUnknownCall && recentUnknown) {
                    inUnknownCall = true
                    unknownNumber = prefs.lastUnknownNumber
                    callStart = System.currentTimeMillis()
                    handler.postDelayed(longCall, prefs.longCallMinutes * 60_000L)
                    Log.i("DruhyNazor", "Probíhá hovor s neznámým číslem $unknownNumber")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                handler.removeCallbacks(longCall)
                ScamCallActivity.close()
                if (inUnknownCall) {
                    inUnknownCall = false
                    prefs.lastUnknownTime = 0L
                    QuestionActivity.launch(this, unknownNumber)
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(longCall)
        val tm = telephony
        val cb = callback
        if (tm != null && cb != null) {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) {
                tm.unregisterTelephonyCallback(cb)
            } else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
            try {
                context.startForegroundService(Intent(context, GuardService::class.java))
            } catch (e: Exception) {
                Log.w("DruhyNazor", "Hlídání nejde spustit z pozadí", e)
            }
        }

        fun isRunning(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications.any { it.id == Notify.ID_GUARD }
    }
}
