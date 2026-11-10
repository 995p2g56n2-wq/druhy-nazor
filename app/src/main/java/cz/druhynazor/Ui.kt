package cz.druhynazor

import android.content.Context
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
    val OK = Color.rgb(0x1D, 0x7A, 0x3A)
    val WARN = Color.rgb(0xA8, 0x5D, 0x00)
    val BAD = Color.rgb(0xB3, 0x26, 0x1E)

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
            if (center) gravity = Gravity.CENTER_HORIZONTAL
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
}
