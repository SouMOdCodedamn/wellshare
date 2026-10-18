package org.wellshare.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

/** Tiny view helpers so the app needs no layout XML and no extra libraries. */
class Ui(private val ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val bg = if (dark) 0xFF151816.toInt() else 0xFFF6F5F1.toInt()
    val surface = if (dark) 0xFF1F2420.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (dark) 0xFFE8ECE9.toInt() else 0xFF1B1F1C.toInt()
    val muted = if (dark) 0xFF9AA59F.toInt() else 0xFF5F6B64.toInt()
    val accent = if (dark) 0xFF7CC4A8.toInt() else 0xFF2F6B57.toInt()
    val onAccent = if (dark) 0xFF0E1A15.toInt() else 0xFFFFFFFF.toInt()
    val track = if (dark) 0xFF2C332E.toInt() else 0xFFE7EAE6.toInt()
    val warn = if (dark) 0xFFF2B36B.toInt() else 0xFF9A5B13.toInt()
    val error = if (dark) 0xFFFF9A8F.toInt() else 0xFFB3261E.toInt()
    val sos = if (dark) 0xFFE5534B.toInt() else 0xFFC62828.toInt()
    val onSos = 0xFFFFFFFF.toInt()
    val warnBg = if (dark) 0xFF3A2E1C.toInt() else 0xFFFFF1DC.toInt()

    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun column(padding: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(): LinearLayout = column(16).apply {
        background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(16).toFloat() }
    }

    fun title(s: String) = label(s, 26f, ink, bold = true)
    fun heading(s: String) = label(s, 18f, ink, bold = true)
    fun body(s: String) = label(s, 15f, ink).apply { setLineSpacing(0f, 1.25f) }
    fun small(s: String, color: Int = muted) = label(s, 13f, color).apply { setLineSpacing(0f, 1.2f) }
    fun big(s: String) = label(s, 40f, ink, bold = true)

    fun label(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) onAccent else accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            if (primary) setColor(accent) else { setColor(Color.TRANSPARENT); setStroke(dp(1), accent) }
        }
        minHeight = dp(52)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** Big red button for SOS. */
    fun sosButton(s: String, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(onSos)
        background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(sos) }
        minHeight = dp(64)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A warning strip with an optional action. */
    fun banner(text: String, action: String? = null, onClick: (() -> Unit)? = null): LinearLayout = column(14).apply {
        background = GradientDrawable().apply { setColor(warnBg); cornerRadius = dp(14).toFloat() }
        addView(label(text, 14f, ink).apply { setLineSpacing(0f, 1.2f) })
        if (action != null && onClick != null) {
            addView(gap(8))
            addView(label(action, 14f, warn, bold = true).apply {
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { onClick() }
            })
        }
    }

    fun input(hint: String, caps: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setTextColor(ink)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        inputType = if (caps) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), track)
        }
    }

    fun gap(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** A thin horizontal progress bar, value 0..1 */
    fun meter(fraction: Float, color: Int = accent): View = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            p.color = track
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
            p.color = color
            val w = (width * fraction.coerceIn(0f, 1f)).coerceAtLeast(height.toFloat())
            c.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), r, r, p)
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)) }
}

/** Seven vertical bars for the last week; today is highlighted. */
class WeekChart(ctx: Context, private val ui: Ui, private val values: List<Long>, private val labels: List<String>) : View(ctx) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, ctx.resources.displayMetrics)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(150))
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(60L).toFloat()
        val labelH = ui.dp(36).toFloat()
        val chartH = height - labelH
        val slot = width / values.size.toFloat()
        val bw = (slot * 0.56f).coerceAtMost(ui.dp(28).toFloat())
        val r = ui.dp(4).toFloat()
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = (chartH - ui.dp(4)) * (v / max)
            val isToday = i == values.lastIndex
            bar.color = if (isToday) ui.accent else (ui.accent and 0x00FFFFFF) or 0x66000000
            if (v > 0) c.drawRoundRect(RectF(cx - bw / 2, chartH - h, cx + bw / 2, chartH), r, r, bar)
            txt.color = if (isToday) ui.ink else ui.muted
            c.drawText(labels[i], cx, chartH + ui.dp(15), txt)
            txt.color = ui.muted
            c.drawText(Format.short(v), cx, chartH + ui.dp(30), txt)
        }
    }
}

object Format {
    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m} min"
            else -> "0 min"
        }
    }

    fun short(sec: Long): String {
        val h = sec / 3600.0
        return if (h >= 1) String.format(java.util.Locale.US, "%.1fh", h) else "${sec / 60}m"
    }
}
