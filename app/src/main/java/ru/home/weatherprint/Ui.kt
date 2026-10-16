package ru.home.weatherprint

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Цвета и общие элементы интерфейса: карточки, кнопки, строки списков, поля ввода. */
object Ui {
    val BG = Color.rgb(242, 244, 248)
    val CARD = Color.WHITE
    val TEXT = Color.rgb(17, 24, 39)
    val SUB = Color.rgb(107, 114, 128)
    val LINE = Color.rgb(229, 231, 235)
    val ACCENT = Color.rgb(37, 99, 235)
    val ACCENT_SOFT = Color.rgb(232, 239, 253)
    val OK = Color.rgb(22, 163, 74)
    val WARN = Color.rgb(217, 119, 6)
    val WARN_SOFT = Color.rgb(255, 247, 230)
    val OFF = Color.rgb(156, 163, 175)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun ripple(content: GradientDrawable, radius: Float) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(30, 0, 0, 0)), content, rounded(Color.WHITE, radius))
}

/**
 * Основа всех экранов: светлый фон, верхняя панель (заголовок, «назад», кнопка справа)
 * и прокручиваемая колонка, в которую экран добавляет карточки.
 */
abstract class BaseActivity : ComponentActivity() {

    protected lateinit var prefs: Prefs
    protected lateinit var content: LinearLayout

    /** Активный принтер мог смениться, пока экран был закрыт, — перечитываем настройки. */
    override fun onResume() {
        if (::prefs.isInitialized) prefs = Prefs(this)
        super.onResume()
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * resources.displayMetrics.density

    /** Собирает экран. [action] — значок и действие справа в панели. */
    protected fun screen(title: String, back: Boolean, action: Pair<Icon, () -> Unit>? = null) {
        prefs = Prefs(this)
        window.statusBarColor = Ui.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) {
            window.navigationBarColor = Ui.BG
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (back) 8 else 20), dp(10), dp(12), dp(6))
        }
        if (back) bar.addView(iconButton(Icon.BACK) { finish() })
        bar.addView(TextView(this).apply {
            text = title
            textSize = if (back) 22f else 28f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.TEXT)
            setPadding(dp(if (back) 4 else 0), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { (icon, onClick) -> bar.addView(iconButton(icon, onClick)) }
        root.addView(bar)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun iconButton(icon: Icon, onClick: () -> Unit) = IconView(this).apply {
        this.icon = icon
        color = Ui.TEXT
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, dpf(24f)), dpf(24f))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    /** Белая карточка со скруглёнными углами. */
    fun card(parent: LinearLayout = content, color: Int = Ui.CARD, pad: Int = 16): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(color, dpf(20f))
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            elevation = if (color == Ui.CARD) dpf(1f) else 0f
        }
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        return c
    }

    fun sectionTitle(t: String, parent: LinearLayout = content) = TextView(this).apply {
        text = t.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(Ui.SUB)
        setPadding(dp(8), dp(20), 0, 0)
    }.also { parent.addView(it) }

    fun label(t: CharSequence, parent: LinearLayout, size: Float = 15f, color: Int = Ui.TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setLineSpacing(0f, 1.15f)
        }.also { parent.addView(it) }

    /** Кнопка: [filled] — синяя основная, иначе — светло-синяя второстепенная. */
    fun button(t: String, parent: LinearLayout, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(if (filled) Color.WHITE else Ui.ACCENT)
        background = Ui.ripple(Ui.rounded(if (filled) Ui.ACCENT else Ui.ACCENT_SOFT, dpf(24f)), dpf(24f))
        setPadding(dp(16), dp(13), dp(16), dp(13))
        setOnClickListener { onClick() }
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
    }

    /** Строка списка: значок в кружке, заголовок, подпись и стрелка. Возвращает поле подписи. */
    fun listItem(
        parent: LinearLayout,
        icon: Icon,
        title: String,
        subtitle: String? = null,
        divider: Boolean = true,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): TextView {
        if (divider && parent.childCount > 0) parent.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(56) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, dpf(12f)), dpf(12f))
            alpha = if (enabled) 1f else 0.45f
            setOnClickListener { onClick() }
        }
        row.addView(iconBadge(icon, if (enabled) Ui.ACCENT else Ui.OFF, Ui.ACCENT_SOFT, 40))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(Ui.TEXT) })
        val sub = TextView(this).apply {
            text = subtitle ?: ""
            textSize = 13f
            setTextColor(Ui.SUB)
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
        }
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(IconView(this).apply { this.icon = Icon.CHEVRON; color = Ui.OFF }, LinearLayout.LayoutParams(dp(20), dp(20)))
        parent.addView(row)
        return sub
    }

    /** Значок в цветном кружке. */
    fun iconBadge(icon: Icon, fg: Int, bg: Int, sizeDp: Int) = FrameLayout(this).apply {
        background = Ui.rounded(bg, dpf(sizeDp / 2f))
        val pad = dp(sizeDp) / 4
        addView(IconView(this@BaseActivity).apply { this.icon = icon; color = fg; this.bg = bg; setPadding(pad, pad, pad, pad) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Поле ввода с подписью сверху. */
    fun field(parent: LinearLayout, label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Ui.SUB); setPadding(dp(4), dp(12), 0, dp(6))
        })
        return EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            textSize = 16f
            setTextColor(Ui.TEXT)
            background = Ui.rounded(Ui.BG, dpf(12f))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            ellipsize = TextUtils.TruncateAt.END
        }.also { parent.addView(it) }
    }

    fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
}

/** Точка-индикатор состояния перед текстом. */
fun Activity.statusText(ok: Boolean?, text: String): CharSequence {
    val sb = android.text.SpannableStringBuilder("● ").append(text)
    val color = when (ok) { true -> Ui.OK; false -> Ui.OFF; null -> Ui.WARN }
    sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
    return sb
}
