package cz.druhynazor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/** MMS zkušební verze neřeší, Android ale vyžaduje, aby aplikace na zprávy tuto část měla. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("DruhyNazor", "Přišla MMS, zkušební verze MMS nezobrazuje")
    }
}

/** Rychlá odpověď SMS z obrazovky příchozího hovoru („Teď nemůžu mluvit“). */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            val number = intent?.data?.schemeSpecificPart
            if (!text.isNullOrBlank() && !number.isNullOrBlank()) {
                SmsStore.send(this, number, text)
            }
        } catch (e: Exception) {
            Log.w("DruhyNazor", "Rychlou odpověď se nepodařilo odeslat", e)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Po zapnutí telefonu znovu spustí hlídání hovorů. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            GuardService.start(context)
        }
    }
}
