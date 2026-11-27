package org.sippy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal object Palette {
    var isDark = false
        private set
    fun useDark(enabled: Boolean) { isDark = enabled }
    val background get() = if (isDark) Color.rgb(17, 25, 22) else Color.rgb(244, 247, 243)
    val surface get() = if (isDark) Color.rgb(26, 37, 32) else Color.rgb(253, 254, 251)
    val border get() = if (isDark) Color.rgb(59, 78, 66) else Color.rgb(220, 230, 222)
    val text get() = if (isDark) Color.rgb(230, 240, 232) else Color.rgb(18, 46, 39)
    val secondary get() = if (isDark) Color.rgb(171, 192, 178) else Color.rgb(72, 99, 89)
    val accent get() = if (isDark) Color.rgb(135, 212, 179) else Color.rgb(16, 99, 83)
    val onAccent get() = if (isDark) Color.rgb(18, 46, 39) else Color.WHITE
    val soft get() = if (isDark) Color.rgb(37, 65, 51) else Color.rgb(219, 239, 227)
    val dark = Color.rgb(18, 57, 54)
    val mint = Color.rgb(184, 241, 216)
    val danger get() = if (isDark) Color.rgb(255, 177, 186) else Color.rgb(171, 44, 55)
    val onDanger get() = if (isDark) Color.rgb(64, 12, 24) else Color.WHITE
    val dangerSoft get() = if (isDark) Color.rgb(69, 35, 40) else Color.rgb(251, 228, 228)
    val warning get() = if (isDark) Color.rgb(230, 190, 122) else Color.rgb(127, 77, 12)
}

internal class Ui(private val context: Context) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun column(gap: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap != 0) dividerGap(gap)
    }
    fun row(gap: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap != 0) dividerGap(gap)
    }
    private fun LinearLayout.dividerGap(gap: Int) {
        dividerDrawable = object : android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) {
            override fun getIntrinsicHeight() = dp(gap)
            override fun getIntrinsicWidth() = dp(gap)
        }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    fun shape(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    fun ripple(fill: Int, radius: Int, border: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(if (Palette.isDark) 0x3487D4B3 else 0x24106353),
            shape(fill, radius, border), shape(Color.WHITE, radius))

    fun text(value: String, size: Int = 15, color: Int = Palette.text, weight: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            includeFontPadding = false
            if (weight) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    fun heading(title: String, description: String? = null) = column(7).apply {
        addView(text(title, 29, weight = true))
        if (description != null) addView(text(description, 14, Palette.secondary))
    }

    fun field(hint: String, initial: String = "", type: Int = android.text.InputType.TYPE_CLASS_TEXT) =
        EditText(context).apply {
            this.hint = hint
            contentDescription = hint
            setSingleLine(true)
            inputType = type
            setText(initial)
            textSize = 16f
            setTextColor(Palette.text)
            setHintTextColor(Palette.secondary)
            minimumHeight = dp(50)
            background = shape(Palette.surface, 12, Palette.border)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

    fun labelled(label: String, field: View) = column(7).apply {
        addView(text(label, 13, Palette.secondary, true).also { labelView ->
            if (field.id == View.NO_ID) field.id = View.generateViewId()
            labelView.labelFor = field.id
        })
        val password = field is EditText && when (field.inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_TEXT -> (field.inputType and android.text.InputType.TYPE_MASK_VARIATION) in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            android.text.InputType.TYPE_CLASS_NUMBER -> (field.inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        if (password) {
            val input = field as EditText
            var visible = false
            val eye = action("", "eye", fill = Palette.surface, outlined = true) {}
            fun refreshEye() {
                eye.contentDescription = "${if (visible) "Hide" else "Show"} $label"
                eye.removeAllViews()
                eye.addView(Symbol(context, if (visible) "eye-off" else "eye", Palette.accent),
                    LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            eye.setOnClickListener {
                val start = input.selectionStart; val end = input.selectionEnd
                visible = !visible
                input.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
                if (start >= 0 && end >= 0) input.setSelection(start.coerceAtMost(input.length()), end.coerceAtMost(input.length()))
                refreshEye()
            }
            refreshEye()
            addView(row(6).apply {
                addView(input, weight())
                addView(eye, LinearLayout.LayoutParams(dp(48), dp(50)))
            })
        } else addView(field, LinearLayout.LayoutParams(-1, -2))
    }

    fun action(title: String, icon: String? = null, fill: Int = Palette.soft, tint: Int = Palette.accent,
        outlined: Boolean = false, click: () -> Unit): LinearLayout = row(7).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        background = ripple(fill, 13, if (outlined) Palette.border else null)
        if (icon != null) addView(Symbol(context, icon, tint), LinearLayout.LayoutParams(dp(19), dp(19)))
        if (title.isNotBlank()) addView(text(title, 14, tint, true).apply { gravity = Gravity.CENTER })
        if (title.isNotBlank()) contentDescription = title
        setOnClickListener { click() }
        isFocusable = true
    }

    fun space(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    fun pad(view: View, size: Int) { view.setPadding(dp(size), dp(size), dp(size), dp(size)) }
}

internal class Symbol(context: Context, private val kind: String, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - scale * 24) / 2, (height - scale * 24) / 2)
        canvas.scale(scale, scale)
        fun path(vararg points: Float) {
            val path = Path()
            path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            canvas.drawPath(path, paint)
        }
        fun circle(x: Float, y: Float, r: Float) = canvas.drawCircle(x, y, r, paint)
        when (kind) {
            "dial" -> for (x in listOf(6f, 12f, 18f)) for (y in listOf(5f, 10f, 15f, 20f)) {
                if (y < 20 || x == 12f) circle(x, y, .65f)
            }
            "contacts" -> {
                circle(12f, 8f, 3.2f)
                canvas.drawArc(5f, 13f, 19f, 23f, 180f, 180f, false, paint)
                path(5f, 18f, 5f, 20f, 19f, 20f, 19f, 18f)
            }
            "settings" -> {
                circle(12f, 12f, 3.3f)
                path(10f, 3f, 14f, 3f, 15f, 6f, 18f, 6.2f, 20f, 9f, 18.6f, 12f, 20f, 15f,
                    18f, 17.8f, 15f, 18f, 14f, 21f, 10f, 21f, 9f, 18f, 6f, 17.8f, 4f, 15f,
                    5.4f, 12f, 4f, 9f, 6f, 6.2f, 9f, 6f, 10f, 3f)
            }
            "call", "hangup" -> {
                if (kind == "hangup") canvas.rotate(132f, 12f, 12f)
                val p = Path().apply {
                    moveTo(7f, 3f); lineTo(9.7f, 8.2f); lineTo(7.8f, 10.2f)
                    quadTo(10.2f, 15f, 14f, 16.2f); lineTo(16.1f, 14.3f); lineTo(21f, 17f)
                    quadTo(21f, 21f, 18f, 21f); quadTo(5f, 19.5f, 3f, 6f); quadTo(3f, 3.5f, 7f, 3f)
                }
                canvas.drawPath(p, paint)
            }
            "backspace" -> {
                path(10f, 5f, 21f, 5f, 21f, 19f, 10f, 19f, 3f, 12f, 10f, 5f)
                path(12f, 9f, 17f, 15f); path(17f, 9f, 12f, 15f)
            }
            "dnd" -> { circle(12f, 12f, 9f); path(7f, 12f, 17f, 12f) }
            "eye", "eye-off" -> {
                canvas.drawOval(2f, 6f, 22f, 18f, paint)
                circle(12f, 12f, 3f)
                if (kind == "eye-off") path(3f, 3f, 21f, 21f)
            }
            "info" -> {
                circle(12f, 12f, 9f)
                circle(12f, 7.5f, .6f)
                path(12f, 11f, 12f, 17f)
            }
            "link" -> {
                path(13f, 4f, 20f, 4f, 20f, 11f)
                path(20f, 4f, 10f, 14f)
                path(9f, 5f, 4f, 5f, 4f, 20f, 19f, 20f, 19f, 15f)
            }
            "plus" -> { path(12f, 5f, 12f, 19f); path(5f, 12f, 19f, 12f) }
            "hold" -> { path(9f, 5f, 9f, 19f); path(15f, 5f, 15f, 19f) }
            "resume" -> path(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f)
            "transfer" -> { path(3f, 8f, 20f, 8f, 16f, 4f); path(21f, 16f, 4f, 16f, 8f, 20f) }
            "volume" -> {
                path(4f, 9f, 8f, 9f, 12f, 5f, 12f, 19f, 8f, 15f, 4f, 15f, 4f, 9f)
                canvas.drawArc(12f, 6f, 22f, 18f, -70f, 140f, false, paint)
            }
            "mic" -> {
                canvas.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, paint)
                canvas.drawArc(5f, 5f, 19f, 18f, 0f, 180f, false, paint)
                path(12f, 18f, 12f, 22f); path(9f, 22f, 15f, 22f)
            }
            "history" -> {
                circle(12f, 12f, 8.5f)
                path(12f, 7f, 12f, 12f, 16f, 15f)
            }
            "incoming" -> { path(18f, 6f, 6f, 18f); path(6f, 9f, 6f, 18f, 15f, 18f) }
            "outgoing" -> { path(6f, 18f, 18f, 6f); path(9f, 6f, 18f, 6f, 18f, 15f) }
            "chevron" -> path(9f, 5f, 16f, 12f, 9f, 19f)
            "up" -> path(6f, 14f, 12f, 8f, 18f, 14f)
            "down" -> path(6f, 10f, 12f, 16f, 18f, 10f)
        }
        canvas.restore()
    }
}
