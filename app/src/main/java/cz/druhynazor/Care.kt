package cz.druhynazor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneId

/** Jeden lék: co a kdy. Zadává ho rodina v Pro rodinu, senior nic nepíše. */
data class Med(val id: String, val name: String, val hour: Int, val minute: Int) {
    val time: String get() = "%d:%02d".format(hour, minute)
}

/**
 * Pomocníci na zkoušku: Léky, Jsem v pořádku a Pomoc.
 * Každý jde v Pro rodinu jedním ťuknutím vypnout. Když se po zkoušení ukáže, že jsou zbyteční,
 * stačí smazat Care*.kt, HelpActivity.kt a pár řádků v MainActivity a DruhyNazorActivity.
 *
 * Budíky: každý má klíč, třeba „m|<lék>|0“ (lék, krok 0 = připomenout v čas) nebo „c|2“
 * (Jsem v pořádku, krok 2 = poslat SMS rodině). Naplánovaný čas se ukládá, aby šly budíky
 * po zapnutí telefonu naplánovat znovu, i ty rozběhnuté (opakované připomínky, SMS rodině).
 */
object Care {
    const val LEKY = "lk"
    const val OK = "ok"
    const val POMOC = "po"

    private const val MIN = 60_000L

    /** Léky: připomínat každých 5 minut, dokud senior nepotvrdí. Po hodině naposledy a SMS rodině. */
    val MED_STEPS = LongArray(13) { it * 5 * MIN }
    /** Poslední krok léků, kdy jde SMS rodině (po hodině). */
    const val MED_SMS_STEP = 12
    /** Jsem v pořádku: ptát se každých 15 minut až 6 hodin, dokud senior neodpoví. Po 3 hodinách SMS rodině. */
    val CHECK_STEPS = LongArray(24) { it * 15 * MIN }
    const val CHECK_SMS_STEP = 12

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pece", Context.MODE_PRIVATE)

    // ---------- Zapnuto / vypnuto ----------

    fun enabled(ctx: Context, id: String): Boolean = prefs(ctx).getBoolean("zap_$id", true)

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        prefs(ctx).edit().putBoolean("zap_$id", on).apply()
        if (!on) when (id) {
            LEKY -> plans(ctx).keys.filter { it.startsWith("m|") }.forEach { clear(ctx, it) }
            OK -> plans(ctx).keys.filter { it.startsWith("c|") }.forEach { clear(ctx, it) }
        }
        restore(ctx)
    }

    // ---------- Léky ----------

    fun meds(ctx: Context): List<Med> = try {
        val arr = org.json.JSONArray(prefs(ctx).getString("leky", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Med(o.getString("id"), o.getString("n"), o.getInt("h"), o.getInt("m"))
        }.sortedBy { it.hour * 60 + it.minute }
    } catch (e: Exception) { emptyList() }

    private fun saveMeds(ctx: Context, list: List<Med>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("n", it.name).put("h", it.hour).put("m", it.minute)) }
        prefs(ctx).edit().putString("leky", arr.toString()).apply()
    }

    fun addMed(ctx: Context, name: String, hour: Int, minute: Int) {
        val med = Med(System.currentTimeMillis().toString(36), name, hour, minute)
        saveMeds(ctx, meds(ctx) + med)
        restore(ctx)
    }

    fun removeMed(ctx: Context, id: String) {
        saveMeds(ctx, meds(ctx).filter { it.id != id })
        plans(ctx).keys.filter { it.startsWith("m|$id|") }.forEach { clear(ctx, it) }
    }

    /** Léky, které teď čekají na „Vzala jsem“ (připomenuté v posledních 3 hodinách). */
    fun dueMeds(ctx: Context): List<Med> {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        return meds(ctx).filter { m ->
            val at = p.getLong("kdy_${m.id}", 0L)
            at > 0 && now - at < 3 * 60 * MIN && p.getLong("vzato_${m.id}", 0L) < at
        }
    }

    /** Lék byl dnes potvrzený. */
    fun takenToday(ctx: Context, m: Med): Boolean {
        val t = prefs(ctx).getLong("vzato_${m.id}", 0L)
        return t > 0 && sameDay(t, System.currentTimeMillis())
    }

    /** Senior ťukl na Vzala jsem. Když už rodina dostala SMS, že lék nepotvrdil(a), dostane i tuhle. */
    fun markTaken(ctx: Context, list: List<Med>) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val e = p.edit()
        val late = mutableListOf<Med>()
        for (m in list) {
            e.putLong("vzato_${m.id}", now)
            if (p.getLong("hlaseno_${m.id}", 0L) >= p.getLong("kdy_${m.id}", Long.MAX_VALUE)) late += m
            // Opakované připomínky a SMS rodině už nejsou potřeba
            for (step in 1 until MED_STEPS.size) clear(ctx, "m|${m.id}|$step")
        }
        e.apply()
        if (late.isNotEmpty()) {
            val prefsMain = Prefs(ctx)
            Family.sms(ctx, "Postaráno: ${prefsMain.seniorName} už ${g(prefsMain, "potvrdila", "potvrdil")} " +
                "lék ${late.joinToString(", ") { it.name }}. Nemusíte nic dělat.")
        }
    }

    // ---------- Jsem v pořádku ----------

    fun checkHour(ctx: Context) = prefs(ctx).getInt("ok_h", 9)
    fun checkMinute(ctx: Context) = prefs(ctx).getInt("ok_m", 0)
    fun checkTime(ctx: Context) = "%d:%02d".format(checkHour(ctx), checkMinute(ctx))

    /** Jak často se ptát: 1 = každý den, 3 = každé 3 dny, 7 = jednou týdně. */
    val CHECK_EVERY = listOf(1, 3, 7)
    fun checkEvery(ctx: Context) = prefs(ctx).getInt("ok_kazdych", 1).takeIf { it in CHECK_EVERY } ?: 1
    fun setCheckEvery(ctx: Context, days: Int) = prefs(ctx).edit().putInt("ok_kazdych", days).apply()
    fun everyLabel(days: Int) = when (days) { 3 -> "každé 3 dny"; 7 -> "jednou týdně"; else -> "každý den" }
    /** Do kdy musí senior odpovědět, jinak SMS rodině. */
    fun deadlineTime(ctx: Context): String {
        val total = checkHour(ctx) * 60 + checkMinute(ctx) + (CHECK_STEPS[CHECK_SMS_STEP] / MIN).toInt()
        return "%d:%02d".format((total / 60) % 24, total % 60)
    }

    fun setCheckTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit().putInt("ok_h", hour).putInt("ok_m", minute).apply()
        clear(ctx, "c|0")
        restore(ctx)
    }

    /** Ptá se teď aplikace „Jak se máte?“ a senior ještě neodpověděl. */
    fun checkPending(ctx: Context): Boolean {
        val p = prefs(ctx)
        val at = p.getLong("kdy_c", 0L)
        return at > 0 && System.currentTimeMillis() - at < 6 * 60 * MIN && p.getLong("odpoved_c", 0L) < at
    }

    /** Senior odpověděl. good = Dobře, jinak Není mi dobře. */
    fun answerCheck(ctx: Context, good: Boolean) {
        val p = prefs(ctx)
        // Rodina už dostala SMS, že neodpovídá (a jde o dnešní otázku): dát vědět, že se ozval(a)
        val late = checkPending(ctx) && p.getLong("hlaseno_c", 0L) >= p.getLong("kdy_c", Long.MAX_VALUE)
        p.edit().putLong("odpoved_c", System.currentTimeMillis()).apply()
        for (step in 1 until CHECK_STEPS.size) clear(ctx, "c|$step")
        val prefsMain = Prefs(ctx)
        if (!good) {
            Family.sms(ctx, "Postaráno: ${prefsMain.seniorName} dnes na otázku Jak se máte ${g(prefsMain, "odpověděla", "odpověděl")}: " +
                "Není mi dobře. Zavolejte ${g(prefsMain, "jí", "mu")} prosím co nejdřív.")
        } else if (late) {
            Family.sms(ctx, "Postaráno: ${prefsMain.seniorName} se už ${g(prefsMain, "ozvala", "ozval")}: je v pořádku. Nemusíte nic dělat.")
        }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val z = ZoneId.systemDefault()
        return Instant.ofEpochMilli(a).atZone(z).toLocalDate() == Instant.ofEpochMilli(b).atZone(z).toLocalDate()
    }

    // ---------- Oslovení podle toho, jak seniorovi říkají ----------

    /** „nepotvrdila“ / „nepotvrdil“: Taťka, Děda a „Váš blízký“ jsou v mužském rodě. */
    fun g(p: Prefs, fem: String, masc: String) = if (p.seniorMale || p.seniorName == Prefs.OTHER) masc else fem

    /** Text tlačítka pro seniora. U „Váš blízký“ nevíme, jestli je to muž, nebo žena. */
    fun takenLabel(p: Prefs) = when {
        p.seniorMale -> "Vzal jsem"
        p.seniorName == Prefs.OTHER -> "Hotovo, vzato"
        else -> "Vzala jsem"
    }

    // ---------- Budíky ----------

    /** Příští okamžik v hour:minute, který je až po now. Čisté počítání, kvůli testům. */
    fun nextAt(now: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var t = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        if (t <= now) t = today.plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        return t
    }

    private fun plans(ctx: Context): Map<String, Long> =
        prefs(ctx).all.filterKeys { it.startsWith("plan_") }
            .mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("plan_") to it } }.toMap()

    private fun pi(ctx: Context, key: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(ctx, key.hashCode(),
            Intent(ctx, CareReceiver::class.java).setAction("cz.druhynazor.PECE").putExtra("klic", key),
            flags or PendingIntent.FLAG_IMMUTABLE)

    fun set(ctx: Context, key: String, at: Long) {
        prefs(ctx).edit().putLong("plan_$key", at).apply()
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val intent = pi(ctx, key)!!
        try {
            // Přesný čas, když to telefon dovolí. Jinak telefon budík může o pár minut posunout.
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun clear(ctx: Context, key: String) {
        prefs(ctx).edit().remove("plan_$key").apply()
        pi(ctx, key, PendingIntent.FLAG_NO_CREATE)?.let { ctx.getSystemService(AlarmManager::class.java)?.cancel(it) }
    }

    /**
     * Naplánovat všechno znovu. Volá se po zapnutí telefonu, po aktualizaci, při otevření aplikace
     * a po každé změně v Pro rodinu. Dá se volat kolikrát chceme, nic se nezdvojí.
     */
    fun restore(ctx: Context) {
        val now = System.currentTimeMillis()
        var soon = 0L
        for ((key, at) in plans(ctx)) {
            when {
                at > now -> set(ctx, key, at)
                // Prošvihnutý budík (telefon byl vypnutý): spustit hned, když je to nejvýš 2 hodiny
                now - at < 120 * MIN -> set(ctx, key, now + 5_000 + 3_000 * soon++)
                else -> clear(ctx, key)
            }
        }
        val planned = plans(ctx)
        if (enabled(ctx, LEKY)) for (m in meds(ctx)) {
            if ("m|${m.id}|0" !in planned) set(ctx, "m|${m.id}|0", nextAt(now, m.hour, m.minute))
        }
        if (enabled(ctx, OK) && "c|0" !in planned) set(ctx, "c|0", nextAt(now, checkHour(ctx), checkMinute(ctx)))
    }

    /** Budík zazvonil. */
    fun fire(ctx: Context, key: String) {
        val p = prefs(ctx)
        p.edit().remove("plan_$key").apply()
        val parts = key.split('|')
        val now = System.currentTimeMillis()
        when (parts[0]) {
            "m" -> {
                if (!enabled(ctx, LEKY) || parts.size != 3) return
                val med = meds(ctx).firstOrNull { it.id == parts[1] } ?: return
                val step = parts[2].toIntOrNull() ?: return
                if (step == 0) {
                    // Zítra znovu, a rozběhnout opakování
                    set(ctx, "m|${med.id}|0", nextAt(now + MIN, med.hour, med.minute))
                    p.edit().putLong("kdy_${med.id}", now).apply()
                    for (s in 1 until MED_STEPS.size) set(ctx, "m|${med.id}|$s", now + MED_STEPS[s])
                }
                if (p.getLong("vzato_${med.id}", 0L) >= p.getLong("kdy_${med.id}", 0L)) return
                if (step != MED_SMS_STEP) CareActivity.showMeds(ctx)
                else {
                    CareActivity.showMeds(ctx)
                    p.edit().putLong("hlaseno_${med.id}", now).apply()
                    val prefsMain = Prefs(ctx)
                    Family.sms(ctx, "Postaráno: ${prefsMain.seniorName} zatím ${g(prefsMain, "nepotvrdila", "nepotvrdil")}, " +
                        "že si ${g(prefsMain, "vzala", "vzal")} lék ${med.name} (v ${med.time}). " +
                        "Zkuste ${g(prefsMain, "jí", "mu")} zavolat.")
                }
            }
            "c" -> {
                if (!enabled(ctx, OK) || parts.size != 2) return
                val step = parts[1].toIntOrNull() ?: return
                if (step == 0) {
                    set(ctx, "c|0", nextAt(now + MIN, checkHour(ctx), checkMinute(ctx)))
                    // Senior už dnes odpověděl sám (dlaždice na úvodní obrazovce): neptat se znovu
                    val answered = p.getLong("odpoved_c", 0L)
                    if (answered > 0 && sameDay(answered, now)) return
                    // Ptát se jen každé 3 dny / jednou týdně: od poslední otázky nebo odpovědi ještě neuplynulo dost
                    val every = checkEvery(ctx)
                    val last = maxOf(answered, p.getLong("kdy_c", 0L))
                    if (every > 1 && last > 0 && now - last < every * 24 * 60 * MIN - 3 * 60 * MIN) return
                    p.edit().putLong("kdy_c", now).apply()
                    for (s in 1 until CHECK_STEPS.size) set(ctx, "c|$s", now + CHECK_STEPS[s])
                }
                if (p.getLong("odpoved_c", 0L) >= p.getLong("kdy_c", 0L)) return
                if (step != CHECK_SMS_STEP) CareActivity.showCheck(ctx)
                else {
                    CareActivity.showCheck(ctx)
                    p.edit().putLong("hlaseno_c", now).apply()
                    val prefsMain = Prefs(ctx)
                    Family.sms(ctx, "Postaráno: ${prefsMain.seniorName} dnes do ${deadlineTime(ctx)} ${g(prefsMain, "neodpověděla", "neodpověděl")} " +
                        "na otázku Jak se máte. Zkuste ${g(prefsMain, "jí", "mu")} zavolat.")
                }
            }
        }
    }

    /** Jednou SMS rodině, že přibyli noví pomocníci. */
    fun announce(ctx: Context) {
        val p = prefs(ctx)
        val prefsMain = Prefs(ctx)
        if (p.getBoolean("ohlaseno", false) || prefsMain.familyContacts.isEmpty()) return
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        p.edit().putBoolean("ohlaseno", true).apply()
        Family.sms(ctx, "Postaráno: Na telefonu ${prefsMain.seniorGenitive} jsou na zkoušku 3 noví pomocníci. " +
            "Pomoc: červené tlačítko vám zavolá a pošle polohu. " +
            "Jsem v pořádku: v ${checkTime(ctx)} se zeptá, jak se má, a když do ${deadlineTime(ctx)} neodpoví, přijde vám SMS. " +
            "Léky: připomene lék nahlas, když ho zadáte v Pro rodinu. Vypnout jde každý tamtéž.", maxParts = 3)
    }
}

class CareReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra("klic") ?: return
        try { Care.fire(ctx, key) } catch (e: Exception) { Log.w("DruhyNazor", "Budík $key selhal", e) }
    }
}
