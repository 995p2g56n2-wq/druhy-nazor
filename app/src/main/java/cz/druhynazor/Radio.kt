package cz.druhynazor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Rádio: dlaždice otevře oficiální aplikaci Českého rozhlasu mujRozhlas (zdarma, Google Play cz.rozhlas.mujrozhlas).
 * Vysílání rozhlasu přímo v naší aplikaci zatím nepouštíme: svolení Českého rozhlasu se nepodařilo ověřit (28. 11. 2026).
 */
object Radio {
    const val ID = "ra"
    const val PKG = "cz.rozhlas.mujrozhlas"

    fun enabled(ctx: Context) = Care.enabled(ctx, ID)

    fun installed(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    fun open(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage(PKG)
        if (i != null) { a.startActivity(i); return }
        install(a)
    }

    fun install(a: Activity) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG")))
        } catch (e: Exception) {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PKG")))
        }
    }
}
