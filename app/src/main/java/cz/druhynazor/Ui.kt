package cz.druhynazor

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ScrollView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Jednoduché stavební kostky obrazovek: velké písmo, velká tlačítka. */
object Ui {
    val BG = Color.rgb(0xEE, 0xF2, 0xF1)
    val CARD = Color.WHITE
    val INK = Color.rgb(0x14, 0x30, 0x2C)
    val MUTED = Color.rgb(0x56, 0x70, 0x6B)
    val ACCENT = Color.rgb(0x0F, 0x6B, 0x5C)
    val ACCENT_SOFT = Color.rgb(0xDC, 0xEF, 0xE9)
    val OK_SOFT = Color.rgb(0xE1, 0xF3, 0xE6)
    val WARN_SOFT = Color.rgb(0xFB, 0xEE, 0xD9)
    val BAD_SOFT = Color.rgb(0xFB, 0xE3, 0xE0)
    val LINE = Color.rgb(0xCF, 0xDC, 0xD8)
    val OK = Color.rgb(0x1D, 0x7A, 0x3A)
    val WARN = Color.rgb(0xA8, 0x5D, 0x00)
    val BAD = Color.rgb(0xB3, 0x26, 0x1E)
    /** Jen pro tlačítko WhatsApp, aby se nepletlo s ostatními zelenými. */
    val BLUE = Color.rgb(0x1E, 0x5A, 0xC8)

    fun color(level: ScamCheck.Level) = when (level) {
        ScamCheck.Level.SCAM -> BAD
        ScamCheck.Level.WARN -> WARN
        ScamCheck.Level.OK -> OK
    }

    fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int, ctx: Context, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = ctx.dp(radiusDp).toFloat()
            if (stroke != null) setStroke(ctx.dp(2), stroke)
        }

    fun column(ctx: Context, padDp: Int = 0, gapDp: Int = 12): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, ctx.dp(gapDp)); setColor(Color.TRANSPARENT) }
        }

    fun card(ctx: Context, gapDp: Int = 10): LinearLayout =
        column(ctx, 18, gapDp).apply { background = rounded(CARD, 20, ctx) }

    fun text(ctx: Context, s: CharSequence, sp: Float = 19f, bold: Boolean = false, color: Int = INK, center: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) {
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            setLineSpacing(0f, 1.15f)
        }

    fun button(ctx: Context, label: String, bg: Int = ACCENT, fg: Int = Color.WHITE, sp: Float = 21f, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            background = rounded(bg, 18, ctx, if (bg == CARD) ACCENT else null)
            setPadding(ctx.dp(16), ctx.dp(18), ctx.dp(16), ctx.dp(18))
            stateListAnimator = null
            setOnClickListener(onClick)
        }

    fun input(ctx: Context, value: String, hint: String, phone: Boolean = false, multi: Boolean = false): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(INK)
            background = rounded(BG, 12, ctx, Color.rgb(0xCF, 0xDC, 0xD8))
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            inputType = when {
                phone -> InputType.TYPE_CLASS_PHONE
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
        }

    fun matchWidth(v: View): View {
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return v
    }

    /**
     * Obrazovka, která si při překreslení pamatuje, kam byla posunutá, a pozná, že se jí zrovna někdo dotýká.
     * Dřív se při každém překreslení (třeba 3 vteřiny po otevření) celá obrazovka vrátila nahoru
     * a ťuknutí pak trefilo jiné tlačítko.
     */
    class KeepScroll(ctx: Context) : ScrollView(ctx) {
        private var touching = false
        private var lastTouch = 0L
        /** Prst je na displeji nebo byl před chvílí (obrazovka ještě dojíždí). */
        val busy: Boolean get() = touching || SystemClock.uptimeMillis() - lastTouch < 1500

        init {
            // Po překreslení si Android sám vybere nějaké textové pole (třeba Obec u Počasí) a odroluje k němu.
            // Proto má výchozí zaměření samotný posuvník, pole dostane zaměření až ťuknutím.
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            lastTouch = SystemClock.uptimeMillis()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun current(a: Activity): KeepScroll? =
        a.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? KeepScroll

    /** Ukázat obsah. keep = stejná obrazovka, jen obnovená: zůstat na stejném místě. */
    fun show(a: Activity, root: View, bg: Int = BG, keep: Boolean = true) {
        val old = if (keep) current(a) else null
        if (old != null) {
            val y = old.scrollY
            old.removeAllViews()
            old.setBackgroundColor(bg)
            old.addView(root)
            old.requestFocus()
            old.scrollTo(0, y)
            old.post { old.scrollTo(0, y) }
            return
        }
        a.setContentView(KeepScroll(a).apply { setBackgroundColor(bg); isFillViewport = true; addView(root) })
    }

    /** Zeptat se „Opravdu…?“ s velkými tlačítkami Ano / Ne, ať omylem ťuknutí nic nesmaže. */
    fun confirm(a: Activity, question: String, yes: String = "Ano, odebrat", onYes: () -> Unit) {
        val box = column(a, 20, 14)
        box.addView(text(a, question, 24f, bold = true, center = true))
        val d = android.app.AlertDialog.Builder(a).setView(box).create()
        box.addView(button(a, yes, BAD, sp = 22f) { d.dismiss(); onYes() }.apply { minHeight = a.dp(80) })
        box.addView(button(a, "Ne, nechat", OK, sp = 22f) { d.dismiss() }.apply { minHeight = a.dp(80) })
        d.show()
    }

    /** Překreslit, až se člověk přestane dotýkat displeje (aby se mu tlačítko neposunulo pod prstem). */
    fun whenIdle(a: Activity, tries: Int = 20, action: () -> Unit) {
        if (a.isFinishing) return
        val v = current(a)
        if (v != null && v.busy && tries > 0) { v.postDelayed({ whenIdle(a, tries - 1, action) }, 700); return }
        action()
    }
}
