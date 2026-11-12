package cz.druhynazor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import cz.druhynazor.Ui.dp
import java.util.Date

/** Hlavní obrazovka: zprávy nahoře, pod nimi nastavení zkušební verze. */
class MainActivity : Activity() {

    private val perms: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.setup(this)
        handleSendTo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSendTo(intent)
    }

    /** Jiná aplikace chce poslat SMS (např. tlačítko „Napsat zprávu“ v kontaktech). */
    private fun handleSendTo(i: Intent?) {
        if (i?.action == Intent.ACTION_SENDTO || i?.action == Intent.ACTION_SEND) {
            val number = i.data?.schemeSpecificPart ?: ""
            startActivity(Intent(this, MessageActivity::class.java).putExtra(MessageActivity.EXTRA_ADDRESS, number))
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermsGranted()) GuardService.start(this)
        render()
    }

    private fun allPermsGranted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun roleHeld(role: String): Boolean {
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(role) && rm.isRoleHeld(role)
    }

    private fun requestRole(role: String, code: Int) {
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(role)) {
            Toast.makeText(this, "Tento telefon to nepodporuje.", Toast.LENGTH_LONG).show(); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(role), code)
    }

    private fun canFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true

    private fun render() {
        val prefs = Prefs(this)
        val root = Ui.column(this, 16, 16).apply { setBackgroundColor(Ui.BG) }

        root.addView(Ui.text(this, "Druhý názor", 32f, bold = true))

        // ---- Nastavení telefonu (jen poprvé) ----
        val smsOk = roleHeld(RoleManager.ROLE_SMS)
        val callOk = roleHeld(RoleManager.ROLE_CALL_SCREENING)
        val permOk = allPermsGranted()
        val fsOk = canFullScreen()
        val guardOk = GuardService.isRunning(this)
        val chatOk = ChatWatcher.isEnabled(this)
        val overlayOk = ScamCallActivity.canShow(this)

        if (!(smsOk && callOk && permOk && fsOk && guardOk && chatOk && overlayOk)) {
            val setup = Ui.card(this)
            setup.addView(Ui.text(this, "Nastavení telefonu", 22f, bold = true))
            setup.addView(Ui.text(this, "Projděte body shora dolů. Hotové body mají ✅.", 16f, color = Ui.MUTED))
            setup.addView(step("Povolit oprávnění (zprávy, telefon, upozornění)", permOk) {
                requestPermissions(perms, 10)
            })
            setup.addView(step("Nastavit jako aplikaci na zprávy", smsOk) { requestRole(RoleManager.ROLE_SMS, 11) })
            setup.addView(step("Zapnout ochranu hovorů", callOk) { requestRole(RoleManager.ROLE_CALL_SCREENING, 12) })
            setup.addView(step("Povolit otázku přes celou obrazovku", fsOk) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
            })
            setup.addView(step("Spustit hlídání hovorů", guardOk) {
                if (!permOk) Toast.makeText(this, "Nejdřív povolte oprávnění (první bod).", Toast.LENGTH_LONG).show()
                GuardService.start(this); root.postDelayed({ render() }, 800)
            })
            setup.addView(step("Povolit varování přes celou obrazovku při hovoru", overlayOk) {
                Toast.makeText(this, "Zapněte přepínač u „Druhý názor“.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            setup.addView(step("Hlídat WhatsApp a Messenger", chatOk) {
                Toast.makeText(this, "Najděte „Druhý názor“ a zapněte ho.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            if (!chatOk) {
                setup.addView(Ui.text(this,
                    "Je-li přepínač šedý („Omezené nastavení“): Nastavení → Aplikace → Druhý názor → ⋮ vpravo nahoře → Povolit omezená nastavení. Pak to zkuste znovu.",
                    15f, color = Ui.MUTED))
            }
            root.addView(setup)
        } else {
            root.addView(Ui.text(this, "✅ Ochrana je zapnutá", 20f, bold = true, color = Ui.OK))
        }

        // ---- Zprávy ----
        root.addView(Ui.text(this, "Zprávy", 24f, bold = true))
        if (!permOk) {
            root.addView(Ui.text(this, "Zprávy se ukážou po povolení oprávnění.", 17f, color = Ui.MUTED))
        } else {
            val list = SmsStore.latest(this)
            if (list.isEmpty()) root.addView(Ui.text(this, "Zatím žádné zprávy.", 17f, color = Ui.MUTED))
            for (sms in list) root.addView(messageRow(sms))
        }

        // ---- Zkušební nastavení ----
        val cfg = Ui.card(this)
        cfg.addView(Ui.text(this, "Nastavení pro test", 22f, bold = true))

        cfg.addView(Ui.text(this, "Jak oslovit seniora v upozornění", 16f, color = Ui.MUTED))
        val senior = Ui.input(this, prefs.seniorName, "Babička"); cfg.addView(senior)
        cfg.addView(Ui.text(this, "Kdo z rodiny dostává upozornění", 16f, color = Ui.MUTED))
        val famName = Ui.input(this, prefs.familyName, "Tomáš"); cfg.addView(famName)
        cfg.addView(Ui.text(this, "Jeho telefon (pro tlačítko Zavolat)", 16f, color = Ui.MUTED))
        val famPhone = Ui.input(this, prefs.familyPhone, "777 123 456", phone = true); cfg.addView(famPhone)
        cfg.addView(Ui.text(this, "Čísla, která se v testu chovají jako podvodníci (oddělte čárkou)", 16f, color = Ui.MUTED))
        val scam = Ui.input(this, prefs.testScamNumbers, "např. 777 111 222", multi = true); cfg.addView(scam)
        cfg.addView(Ui.text(this, "Po kolika minutách hovoru s neznámým číslem upozornit rodinu", 16f, color = Ui.MUTED))
        val minutes = Ui.input(this, prefs.longCallMinutes.toString(), "3", phone = true); cfg.addView(minutes)
        val block = android.widget.CheckBox(this).apply {
            text = "Podvodná čísla rovnou blokovat (telefon vůbec nezazvoní)"
            textSize = 17f
            setTextColor(Ui.INK)
            isChecked = prefs.blockScamCalls
        }
        cfg.addView(block)
        cfg.addView(Ui.button(this, "Uložit nastavení", Ui.ACCENT, sp = 19f) {
            prefs.seniorName = senior.text.toString().trim().ifEmpty { "Babička" }
            prefs.familyName = famName.text.toString().trim().ifEmpty { "Tomáš" }
            prefs.familyPhone = famPhone.text.toString().trim()
            prefs.testScamNumbers = scam.text.toString().trim()
            prefs.longCallMinutes = minutes.text.toString().trim().toIntOrNull() ?: 3
            prefs.blockScamCalls = block.isChecked
            Toast.makeText(this, "Uloženo", Toast.LENGTH_SHORT).show()
        })

        cfg.addView(Ui.text(this, "Kanál pro upozornění rodině", 18f, bold = true))
        cfg.addView(Ui.text(this,
            "Na iPhone člena rodiny nainstalujte zdarma aplikaci „ntfy“ a přihlaste se k odběru tohoto kanálu:", 16f, color = Ui.MUTED))
        cfg.addView(Ui.text(this, prefs.familyChannel, 20f, bold = true, color = Ui.ACCENT).apply { setTextIsSelectable(true) })
        cfg.addView(Ui.button(this, "Zkopírovat kanál", Ui.CARD, Ui.ACCENT, sp = 17f) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("kanal", prefs.familyChannel))
            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
        })
        cfg.addView(Ui.button(this, "Poslat rodině zkušební upozornění", Ui.CARD, Ui.ACCENT, sp = 17f) {
            Family.notify(this, "Druhý názor: zkouška", "Upozornění funguje. Takhle vám přijde zpráva, když bude seniorovi hrozit podvod.", urgent = false)
            Toast.makeText(this, "Odesláno, podívejte se do ntfy na iPhonu", Toast.LENGTH_LONG).show()
        })
        cfg.addView(Ui.button(this, "Ukázat varování před podvodníkem", Ui.CARD, Ui.ACCENT, sp = 17f) {
            startActivity(Intent(this, ScamCallActivity::class.java).putExtra(ScamCallActivity.EXTRA_NUMBER, "777 000 000 (zkouška)"))
        })
        cfg.addView(Ui.button(this, "Ukázat otázku po hovoru", Ui.CARD, Ui.ACCENT, sp = 17f) {
            startActivity(Intent(this, QuestionActivity::class.java).putExtra(QuestionActivity.EXTRA_NUMBER, "777 000 000 (zkouška)"))
        })
        cfg.addView(Ui.text(this,
            "Až budete test končit: Nastavení telefonu → Aplikace → Výchozí aplikace → SMS a vraťte původní aplikaci Zprávy.",
            15f, color = Ui.MUTED))
        root.addView(cfg)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG); isFillViewport = true; addView(root)
        })
    }

    private fun step(label: String, done: Boolean, onClick: (View) -> Unit): View =
        if (done) Ui.text(this, "✅  $label", 18f, color = Ui.OK)
        else Ui.button(this, "➜  $label", Ui.ACCENT, sp = 18f, onClick = onClick)

    private fun messageRow(sms: Sms): View {
        val result = if (sms.incoming) ScamCheck.check(sms.body) else null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.CARD, 16, this@MainActivity)
            setPadding(0, dp(12), dp(14), dp(12))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MessageActivity::class.java).putExtra(MessageActivity.EXTRA_ID, sms.id))
            }
        }
        val stripe = View(this).apply {
            background = Ui.rounded(if (result == null) Ui.MUTED else Ui.color(result.level), 4, this@MainActivity)
        }
        row.addView(stripe, LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
        val col = Ui.column(this, 0, 4)
        val label = when (result?.level) {
            ScamCheck.Level.SCAM -> "⚠️ PODVOD · "
            ScamCheck.Level.WARN -> "⚠️ POZOR · "
            ScamCheck.Level.OK -> ""
            null -> "Odesláno · "
        }
        col.addView(Ui.text(this, label + sms.address, 17f, bold = true,
            color = if (result == null) Ui.MUTED else if (result.level == ScamCheck.Level.OK) Ui.INK else Ui.color(result.level)))
        col.addView(Ui.text(this, sms.body.take(120) + if (sms.body.length > 120) "…" else "", 16f).apply { maxLines = 3 })
        col.addView(Ui.text(this, DateFormat.format("d. M. HH:mm", Date(sms.date)), 13f, color = Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (allPermsGranted()) GuardService.start(this)
        render()
    }
}
