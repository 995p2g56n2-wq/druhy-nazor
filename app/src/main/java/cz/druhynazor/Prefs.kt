package cz.druhynazor

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** Nastavení zkušební verze. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("druhy_nazor", Context.MODE_PRIVATE)

    var familyName: String
        get() = p.getString("family_name", "Tomáš") ?: "Tomáš"
        set(v) = p.edit().putString("family_name", v).apply()

    var familyPhone: String
        get() = p.getString("family_phone", "") ?: ""
        set(v) = p.edit().putString("family_phone", v).apply()

    var seniorName: String
        get() = p.getString("senior_name", "Babička") ?: "Babička"
        set(v) = p.edit().putString("senior_name", v).apply()

    /** Kanál v aplikaci ntfy, přes který chodí upozornění rodině. */
    var familyChannel: String
        get() {
            val existing = p.getString("family_channel", null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = "druhy-nazor-" + (1..10).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }.joinToString("")
            p.edit().putString("family_channel", fresh).apply()
            return fresh
        }
        set(v) = p.edit().putString("family_channel", v).apply()

    /** Čísla, která se v testu chovají jako nahlášení podvodníci (oddělená čárkou). */
    var testScamNumbers: String
        get() = p.getString("test_scam", "") ?: ""
        set(v) = p.edit().putString("test_scam", v).apply()

    var blockScamCalls: Boolean
        get() = p.getBoolean("block_scam", false)
        set(v) = p.edit().putBoolean("block_scam", v).apply()

    var longCallMinutes: Int
        get() = p.getInt("long_call_min", 3)
        set(v) = p.edit().putInt("long_call_min", v.coerceIn(1, 60)).apply()

    // Poslední příchozí hovor z neznámého čísla (zapisuje ScreeningService)
    var lastUnknownNumber: String
        get() = p.getString("last_unknown_number", "") ?: ""
        set(v) = p.edit().putString("last_unknown_number", v).apply()

    var lastUnknownTime: Long
        get() = p.getLong("last_unknown_time", 0L)
        set(v) = p.edit().putLong("last_unknown_time", v).apply()

    fun isTestScamNumber(number: String?): Boolean {
        val n = normalize(number ?: return false)
        if (n.isEmpty()) return false
        return testScamNumbers.split(',', ';', '\n')
            .map { normalize(it) }
            .any { it.isNotEmpty() && it == n }
    }

    companion object {
        /** Posledních 9 číslic, aby +420 777 123 456 a 777123456 byly totéž. */
        fun normalize(number: String): String {
            val digits = number.filter { it.isDigit() }
            return if (digits.length > 9) digits.takeLast(9) else digits
        }
    }
}
