package dev.reisarthur.papyrus.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pequenos atalhos para montar interface em código (sem XML), mantendo tudo tipado.
 */
fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.dpf(v: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Papéis de cor do Material 3 (Material You).
 * No Android 12+ vêm das cores dinâmicas do sistema (derivadas do papel de parede);
 * antes disso, uma paleta fixa gerada a partir do vermelho do ícone.
 */
class AppColors(val dark: Boolean) {
    val primary: Int
    val onPrimary: Int
    val primaryContainer: Int
    val onPrimaryContainer: Int
    val secondaryContainer: Int
    val onSecondaryContainer: Int
    val surface: Int
    val surfaceContainerLow: Int
    val surfaceContainer: Int
    val surfaceContainerHigh: Int
    val surfaceContainerHighest: Int
    val onSurface: Int
    val onSurfaceVariant: Int
    val outline: Int
    val outlineVariant: Int

    init {
        val res = dev.reisarthur.papyrus.App.instance.resources
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int) = res.getColor(id, null)
            val a1 = { tone: Int -> c(systemColor("accent1", tone)) }
            val a2 = { tone: Int -> c(systemColor("accent2", tone)) }
            val n1 = { tone: Int -> c(systemColor("neutral1", tone)) }
            val n2 = { tone: Int -> c(systemColor("neutral2", tone)) }
            if (dark) {
                primary = a1(200); onPrimary = a1(800); primaryContainer = a1(700); onPrimaryContainer = a1(100)
                secondaryContainer = a2(700); onSecondaryContainer = a2(100)
                surface = n1(900); surfaceContainerLow = blend(n1(900), n1(800), 0.25f)
                surfaceContainer = blend(n1(900), n1(800), 0.45f); surfaceContainerHigh = blend(n1(900), n1(800), 0.7f)
                surfaceContainerHighest = n1(800)
                onSurface = n1(100); onSurfaceVariant = n2(200); outline = n2(400); outlineVariant = n2(700)
            } else {
                primary = a1(600); onPrimary = a1(0); primaryContainer = a1(100); onPrimaryContainer = a1(900)
                secondaryContainer = a2(100); onSecondaryContainer = a2(900)
                surface = n1(10); surfaceContainerLow = blend(n1(10), n1(50), 0.5f)
                surfaceContainer = n1(50); surfaceContainerHigh = blend(n1(50), n1(100), 0.4f)
                surfaceContainerHighest = n1(100)
                onSurface = n1(900); onSurfaceVariant = n2(700); outline = n2(500); outlineVariant = n2(200)
            }
        } else if (dark) {
            primary = 0xFFFFB4A8.toInt(); onPrimary = 0xFF690100.toInt(); primaryContainer = 0xFF882920.toInt(); onPrimaryContainer = 0xFFFFDAD4.toInt()
            secondaryContainer = 0xFF5D3F3B.toInt(); onSecondaryContainer = 0xFFFFDAD4.toInt()
            surface = 0xFF1A1110.toInt(); surfaceContainerLow = 0xFF231918.toInt(); surfaceContainer = 0xFF271D1C.toInt()
            surfaceContainerHigh = 0xFF322826.toInt(); surfaceContainerHighest = 0xFF3D3231.toInt()
            onSurface = 0xFFF1DFDB.toInt(); onSurfaceVariant = 0xFFD8C2BE.toInt(); outline = 0xFFA08C89.toInt(); outlineVariant = 0xFF534341.toInt()
        } else {
            primary = 0xFFA83F31.toInt(); onPrimary = Color.WHITE; primaryContainer = 0xFFFFDAD4.toInt(); onPrimaryContainer = 0xFF410000.toInt()
            secondaryContainer = 0xFFFFDAD4.toInt(); onSecondaryContainer = 0xFF2C1512.toInt()
            surface = 0xFFFFF8F6.toInt(); surfaceContainerLow = 0xFFFFF0EE.toInt(); surfaceContainer = 0xFFFCEAE6.toInt()
            surfaceContainerHigh = 0xFFF6E4E0.toInt(); surfaceContainerHighest = 0xFFF1DFDB.toInt()
            onSurface = 0xFF231917.toInt(); onSurfaceVariant = 0xFF534341.toInt(); outline = 0xFF857370.toInt(); outlineVariant = 0xFFD8C2BE.toInt()
        }
    }

    // Nomes antigos, usados em toda a interface.
    val background get() = surface
    val text get() = onSurface
    val secondary get() = onSurfaceVariant
    val accent get() = primary
    val divider get() = outlineVariant

    /** Cor do "véu" de toque/seleção a partir de uma cor base (state layer do M3). */
    fun stateLayer(base: Int, alpha: Int = 0x1F) = (alpha shl 24) or (base and 0x00FFFFFF)

    private companion object {
        /** IDs das cores dinâmicas do sistema (android.R.color.system_*), só os tons usados. */
        @android.annotation.SuppressLint("InlinedApi")
        fun systemColor(palette: String, tone: Int): Int = when ("$palette$tone") {
            "accent10" -> android.R.color.system_accent1_0
            "accent1100" -> android.R.color.system_accent1_100
            "accent1200" -> android.R.color.system_accent1_200
            "accent1600" -> android.R.color.system_accent1_600
            "accent1700" -> android.R.color.system_accent1_700
            "accent1800" -> android.R.color.system_accent1_800
            "accent1900" -> android.R.color.system_accent1_900
            "accent2100" -> android.R.color.system_accent2_100
            "accent2700" -> android.R.color.system_accent2_700
            "accent2900" -> android.R.color.system_accent2_900
            "neutral110" -> android.R.color.system_neutral1_10
            "neutral150" -> android.R.color.system_neutral1_50
            "neutral1100" -> android.R.color.system_neutral1_100
            "neutral1800" -> android.R.color.system_neutral1_800
            "neutral1900" -> android.R.color.system_neutral1_900
            "neutral2200" -> android.R.color.system_neutral2_200
            "neutral2400" -> android.R.color.system_neutral2_400
            "neutral2500" -> android.R.color.system_neutral2_500
            "neutral2700" -> android.R.color.system_neutral2_700
            else -> error("tom não mapeado: $palette $tone")
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
            return Color.rgb(ch(16), ch(8), ch(0))
        }
    }
}

val Context.appColors: AppColors get() = AppColors(isNightMode())

fun Context.text(
    value: CharSequence = "",
    sizeSp: Float = 16f,
    color: Int = appColors.text,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
}

fun Context.vertical(padding: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        block()
    }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

/**
 * Espaço flexível que empurra os vizinhos numa linha horizontal.
 * Altura 0 de propósito: uma View vazia com WRAP_CONTENT ocupa toda a altura disponível.
 */
fun Context.spacer(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun oval(color: Int, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

/** Aplica o efeito de toque (ripple) padrão do Material sobre um fundo opcional. */
fun View.ripple(rippleColor: Int, content: android.graphics.drawable.Drawable? = null, mask: android.graphics.drawable.Drawable? = null) {
    background = RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask ?: content ?: roundRect(Color.WHITE, 0f))
    isClickable = true
    isFocusable = true
}

/** Botão de texto simples no estilo "flat" do Material. */
fun Context.flatButton(label: String, color: Int = appColors.accent, onClick: () -> Unit): TextView =
    text(label, 14f, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        ripple(0x33000000 or (color and 0xFFFFFF), mask = roundRect(Color.WHITE, dpf(20)))
        setOnClickListener { onClick() }
    }

/**
 * Transição em fade: tira uma "foto" de [target], cobre a tela com ela, aplica [change] por baixo
 * e faz a foto sumir aos poucos. Serve para qualquer troca visual brusca (ex.: tema).
 * [container] precisa ser um FrameLayout que contém [target] (ou o próprio [target]).
 */
fun crossfade(container: android.widget.FrameLayout, target: View, durationMs: Long = 220, change: () -> Unit) {
    if (target.width == 0 || target.height == 0) { change(); return }
    val shot = android.graphics.Bitmap.createBitmap(target.width, target.height, android.graphics.Bitmap.Config.ARGB_8888)
    target.draw(android.graphics.Canvas(shot))
    val cover = android.widget.ImageView(container.context).apply {
        setImageBitmap(shot)
        translationX = if (target === container) 0f else target.left + target.translationX
        translationY = if (target === container) 0f else target.top + target.translationY
    }
    container.addView(cover, android.widget.FrameLayout.LayoutParams(target.width, target.height))
    change()
    cover.animate().alpha(0f).setDuration(durationMs)
        .setInterpolator(android.view.animation.DecelerateInterpolator())
        .withEndAction { container.removeView(cover); shot.recycle() }
        .start()
}
