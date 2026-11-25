package cz.druhynazor

import android.content.Context
import android.content.SharedPreferences

data class FamilyContact(val name: String, val phone: String)

/** Nastavení zkušební verze. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("druhy_nazor", Context.MODE_PRIVATE)
    /** Věci, které patří jen k tomuto telefonu. Nezálohují se, nový telefon je musí projít znovu. */
    private val d: SharedPreferences =
        context.applicationContext.getSharedPreferences("zarizeni", Context.MODE_PRIVATE)

    var familyName: String
        get() = p.getString("family_name", "Rodina") ?: "Rodina"
        set(v) = p.edit().putString("family_name", v).apply()

    var familyPhone: String
        get() = p.getString("family_phone", "") ?: ""
        set(v) = p.edit().putString("family_phone", v).apply()

    /** Kdo z rodiny dostává SMS a může odpovědět ANO / NE. */
    var familyContacts: List<FamilyContact>
        get() {
            val raw = p.getString("family_contacts", null)
            if (raw == null) {
                // Starší verze měla jen jedno jméno a číslo
                val phone = familyPhone
                return if (phone.isNotBlank()) listOf(FamilyContact(familyName, phone)) else emptyList()
            }
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { val o = arr.getJSONObject(it); FamilyContact(o.getString("n"), o.getString("p")) }
            } catch (e: Exception) { emptyList() }
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { arr.put(org.json.JSONObject().put("n", it.name).put("p", it.phone)) }
            p.edit().putString("family_contacts", arr.toString()).apply()
            // Jméno do textů („Standa už o tom ví“) a číslo pro tlačítko Zavolat
            familyName = when (v.size) { 0 -> "Rodina"; 1 -> v[0].name; else -> "Rodina" }
            familyPhone = v.firstOrNull()?.phone ?: ""
        }

    /** Kdo z rodiny má toto číslo, nebo null. */
    fun familyByNumber(number: String?): FamilyContact? {
        val n = normalize(number ?: return null)
        if (n.isEmpty()) return null
        return familyContacts.firstOrNull { normalize(it.phone) == n }
    }

    /** Telefon umí mluvit česky (hlasem Google). */
    var voiceOk: Boolean
        get() = d.getBoolean("voice_ok", false)
        set(v) = d.edit().putBoolean("voice_ok", v).apply()

    /** Původní Zprávy jsou schované z plochy, na ploše je naše obálka. */
    var uklidDone: Boolean
        get() = d.getBoolean("uklid_done", false)
        set(v) = d.edit().putBoolean("uklid_done", v).apply()

    /** Na ploše je naše obálka Zprávy. */
    var envelopePinned: Boolean
        get() = d.getBoolean("envelope_pinned", false)
        set(v) = d.edit().putBoolean("envelope_pinned", v).apply()

    /** Rodině už odešla zkušební zpráva na konci nastavení. */
    var testSent: Boolean
        get() = p.getBoolean("test_sent", false)
        set(v) = p.edit().putBoolean("test_sent", v).apply()

    /** Jak rodina seniorovi říká, v 1. pádě: „Mamka: podvodná SMS“. */
    var seniorName: String
        get() = p.getString("senior_name", "Babička") ?: "Babička"
        set(v) = p.edit().putString("senior_name", v).apply()

    /** Taťka nebo Děda: do zpráv rodině „vyfotil“, ne „vyfotila“. */
    val seniorMale: Boolean get() = seniorName == "Taťka" || seniorName == "Děda"

    /** Totéž ve 2. pádě: „telefon mamky“. */
    val seniorGenitive: String get() = GENITIVE[seniorName] ?: seniorName

    /** Křestní jméno prvního z rodiny do vět jako „Tomáš už o tom ví.“ */
    val familyFirstName: String
        get() = familyName.trim().split(' ').firstOrNull()?.takeIf { it.isNotBlank() } ?: "Rodina"

    /** Kdy naposledy dostala rodina SMS, že ochrana není celá zapnutá (aby nechodila pořád). */
    var setupAlertTime: Long
        get() = d.getLong("setup_alert_time", 0L)
        set(v) = d.edit().putLong("setup_alert_time", v).apply()

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

    /** Zvoní teď hovor od nahlášeného podvodníka (kvůli upozornění na zmeškaný hovor). */
    var ringingScam: Boolean
        get() = p.getBoolean("ringing_scam", false)
        set(v) = p.edit().putBoolean("ringing_scam", v).apply()

    /** Zahraniční čísla, která volala za posledních 7 dní: "cislo|cas;cislo|cas" */
    fun rememberForeignCaller(number: String) {
        val now = System.currentTimeMillis()
        val keep = foreignEntries().filter { now - it.second < WEEK && it.first != normalize(number) }
        val all = keep + (normalize(number) to now)
        p.edit().putString("foreign_callers", all.joinToString(";") { "${it.first}|${it.second}" }).apply()
    }

    fun foreignCallerRecently(number: String): Boolean {
        val n = normalize(number)
        val now = System.currentTimeMillis()
        return foreignEntries().any { it.first == n && now - it.second < WEEK }
    }

    private fun foreignEntries(): List<Pair<String, Long>> =
        (p.getString("foreign_callers", "") ?: "").split(';').mapNotNull {
            val parts = it.split('|')
            if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
        }

    fun isTestScamNumber(number: String?): Boolean {
        val n = normalize(number ?: return false)
        if (n.isEmpty()) return false
        return testScamNumbers.split(',', ';', '\n')
            .map { normalize(it) }
            .any { it.isNotEmpty() && it == n }
    }

    companion object {
        /** Oslovení na obrazovce „Jak vám Tomáš říká?“ → jméno do zpráv rodině. */
        val CALLS = listOf("Mami" to "Mamka", "Tati" to "Taťka", "Babi" to "Babička", "Dědo" to "Děda")
        const val OTHER = "Váš blízký"
        private val GENITIVE = mapOf("Mamka" to "mamky", "Taťka" to "taťky", "Babička" to "babičky",
            "Děda" to "dědy", OTHER to "vašeho blízkého")

        private const val WEEK = 7L * 24 * 3600 * 1000

        /** Číslo začínající +xxx nebo 00xxx, které není české (+420). */
        fun isForeign(number: String): Boolean {
            val t = number.replace(" ", "").replace("-", "")
            val intl = when {
                t.startsWith("+") -> t.drop(1)
                t.startsWith("00") -> t.drop(2)
                else -> return false
            }
            return intl.isNotEmpty() && !intl.startsWith("420")
        }

        /** Posledních 9 číslic, aby +420 777 123 456 a 777123456 byly totéž. */
        fun normalize(number: String): String {
            val digits = number.filter { it.isDigit() }
            return if (digits.length > 9) digits.takeLast(9) else digits
        }
    }
}
