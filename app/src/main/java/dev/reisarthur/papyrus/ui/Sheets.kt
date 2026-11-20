package dev.reisarthur.papyrus.ui

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

typealias DialogAction = (DialogInterface, Int) -> Unit

/**
 * Diálogo no estilo Material 3 (cantos de 28 dp, título grande, botões de texto na cor primária).
 * Tem a mesma "cara" de uso do AlertDialog.Builder, então as telas não precisam saber a diferença.
 */
class M3Alert(private val activity: Activity, private val dark: Boolean) {
    private val colors = AppColors(dark)
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var items: Array<String>? = null
    private var itemAction: DialogAction? = null
    private val buttons = ArrayList<Triple<Int, String, DialogAction?>>()
    private var onDismiss: (() -> Unit)? = null
    private var focusView: android.widget.EditText? = null

    /** Ao abrir, foca este campo e já mostra o teclado. */
    fun showKeyboardFor(field: android.widget.EditText) = apply { focusView = field }

    fun setTitle(t: CharSequence) = apply { title = t }
    fun setMessage(m: CharSequence) = apply { message = m }
    fun setView(v: View) = apply { content = v }
    fun setItems(list: Array<String>, action: DialogAction) = apply { items = list; itemAction = action }
    fun setPositiveButton(label: String, action: DialogAction?) = apply { buttons += Triple(DialogInterface.BUTTON_POSITIVE, label, action) }
    fun setNegativeButton(label: String, action: DialogAction?) = apply { buttons += Triple(DialogInterface.BUTTON_NEGATIVE, label, action) }
    fun setNeutralButton(label: String, action: DialogAction?) = apply { buttons += Triple(DialogInterface.BUTTON_NEUTRAL, label, action) }
    fun setOnDismissListener(action: (DialogInterface) -> Unit) = apply { onDismiss = { action(dialogRef!!) } }

    private var dialogRef: Dialog? = null

    fun show(): Dialog {
        val a = activity
        val dialog = Dialog(a)
        dialogRef = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = a.vertical().apply {
            background = roundRect(colors.surfaceContainerHigh, a.dpf(28))
            setPadding(0, a.dp(24), 0, a.dp(18))
        }
        title?.let {
            root.addView(a.text(it, 22f, colors.onSurface).apply { setPadding(a.dp(24), 0, a.dp(24), a.dp(16)) })
        }
        val body = a.vertical()
        message?.let {
            body.addView(a.text(it, 14.5f, colors.onSurfaceVariant).apply {
                setPadding(a.dp(24), 0, a.dp(24), a.dp(8))
                setLineSpacing(0f, 1.2f)
            })
        }
        items?.forEachIndexed { i, label ->
            body.addView(a.text(label, 16f, colors.onSurface).apply {
                setPadding(a.dp(24), a.dp(14), a.dp(24), a.dp(14))
                ripple(colors.stateLayer(colors.onSurface))
                setOnClickListener { dialog.dismiss(); itemAction?.invoke(dialog, i) }
            })
        }
        content?.let { (it.parent as? ViewGroup)?.removeView(it); body.addView(it) }
        // altura "wrap" com peso 1: se o conteúdo for maior que a tela, só ele encolhe e rola
        root.addView(ScrollView(a).apply { addView(body); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(-1, -2, 1f))

        if (buttons.isNotEmpty()) {
            val row = a.horizontal().apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(a.dp(12), a.dp(12), a.dp(12), 0)
            }
            // ordem do Material: neutro à esquerda, depois negativo e positivo
            val ordered = buttons.sortedBy { (which, _, _) ->
                when (which) { DialogInterface.BUTTON_NEUTRAL -> 0; DialogInterface.BUTTON_NEGATIVE -> 1; else -> 2 }
            }
            ordered.forEach { (which, label, action) ->
                if (which == DialogInterface.BUTTON_NEUTRAL) {
                    row.addView(a.flatButton(label, colors.primary) { dialog.dismiss(); action?.invoke(dialog, which) })
                    row.addView(a.spacer())
                } else {
                    row.addView(a.flatButton(label, colors.primary) { dialog.dismiss(); action?.invoke(dialog, which) })
                }
            }
            root.addView(row, matchWrap())
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(a.resources.displayMetrics.widthPixels - a.dp(48), a.dp(560))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.32f)
            if (focusView != null) {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
        }
        dialog.show()
        focusView?.let { field ->
            field.requestFocus()
            // o pedido do teclado só funciona depois que a janela recebe foco
            field.postDelayed({
                val imm = a.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
        return dialog
    }
}

fun Activity.alert(dark: Boolean): M3Alert = M3Alert(this, dark)

/**
 * Folha inferior do Material 3: alça de arrastar, cantos de 28 dp e cor surfaceContainerLow.
 * - arrastar a aba para baixo fecha (ou volta, se o arrasto for curto);
 * - [setContent] e [recolor] trocam o conteúdo e as cores sem fechar a janela,
 *   então mudar o tema não faz o painel sumir e reaparecer.
 */
class BottomSheet(private val activity: Activity, dark: Boolean) {
    val dialog = Dialog(activity)
    private val handle = View(activity)
    private val scroll = ScrollView(activity)
    private val box: LinearLayout = activity.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
    /** Envolve o painel (para o fade ao trocar o tema) e permite arrastá-lo de qualquer ponto. */
    private val frame = DragFrame().apply { addView(box, android.widget.FrameLayout.LayoutParams(-1, -2)) }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // área de toque da aba maior que o desenho dela, para ser fácil de pegar
        val handleArea = android.widget.FrameLayout(activity).apply {
            addView(handle, android.widget.FrameLayout.LayoutParams(activity.dp(32), activity.dp(4), Gravity.CENTER))
        }
        box.addView(handleArea, LinearLayout.LayoutParams(-1, activity.dp(36)))
        box.addView(scroll, matchWrap())
        recolor(dark)
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.32f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(dev.reisarthur.papyrus.R.style.SheetWindow)
            // o painel desce até a borda da tela (atrás da barra de navegação) em vez de parar
            // acima dela e deixar um "buraco"; o conteúdo recebe a altura da barra como margem
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setDecorFitsSystemWindows(false)
                box.setOnApplyWindowInsetsListener { v, insets ->
                    v.setPadding(0, 0, 0, insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom)
                    insets
                }
            }
        }
    }

    fun setContent(content: View) {
        scroll.removeAllViews()
        scroll.addView(content)
    }

    fun recolor(dark: Boolean) {
        val colors = AppColors(dark)
        box.background = roundRect(colors.surfaceContainerLow, 0f).apply {
            cornerRadii = floatArrayOf(activity.dpf(28), activity.dpf(28), activity.dpf(28), activity.dpf(28), 0f, 0f, 0f, 0f)
        }
        handle.background = roundRect((colors.onSurfaceVariant and 0x00FFFFFF) or 0x66000000, activity.dpf(2))
        if (android.os.Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            dialog.window?.navigationBarColor = colors.surfaceContainerLow
        }
    }

    /** Troca cores/conteúdo com fade (mesma duração do fade do livro). */
    fun crossfade(change: () -> Unit) = crossfade(frame, box, change = change)

    fun setOnDismissListener(l: (() -> Unit)?) = dialog.setOnDismissListener(l?.let { f -> android.content.DialogInterface.OnDismissListener { f() } })
    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()

    /**
     * Arrasto do painel a partir de qualquer ponto dele. Toques e cliques continuam indo para os
     * botões; só um movimento vertical claro vira arrasto, e só quando a rolagem interna já está
     * no limite nessa direção (senão quem rola é o conteúdo).
     * Para baixo: o painel segue o dedo e fecha se desceu bastante (ou rápido). Para cima: o painel
     * não se move, só estica de leve a partir da base e volta, como o efeito de fim de rolagem.
     */
    private inner class DragFrame : android.widget.FrameLayout(activity) {
        private val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var velocity: android.view.VelocityTracker? = null

        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    dragging = false
                    box.animate().cancel()
                    velocity?.recycle()
                    velocity = android.view.VelocityTracker.obtain().apply { addMovement(e) }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    val vertical = kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                    val atLimit = if (dy > 0) !scroll.canScrollVertically(-1) else !scroll.canScrollVertically(1)
                    if (vertical && atLimit) {
                        dragging = true
                        downY = e.rawY // começa a contar daqui: o painel não dá salto
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            return dragging
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> return true // área vazia do painel: começa um possível arrasto
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dy = e.rawY - downY
                    if (!dragging && kotlin.math.abs(dy) > slop) { dragging = true; downY = e.rawY }
                    if (dragging) {
                        val d = e.rawY - downY
                        if (d >= 0) {
                            box.translationY = d
                            box.scaleY = 1f
                        } else {
                            // para cima o painel não sai do lugar: só "estica" um pouquinho a partir da
                            // base, como o efeito de fim de rolagem do Android (no máximo 3%)
                            box.translationY = 0f
                            box.pivotY = box.height.toFloat()
                            box.scaleY = 1f + 0.03f * (1f - 1f / (1f - d / activity.dpf(120)))
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val vt = velocity
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val fling = (vt?.yVelocity ?: 0f) > 1200f && box.translationY > 0f
                    box.pivotY = box.height.toFloat()
                    vt?.recycle()
                    velocity = null
                    dragging = false
                    if (box.translationY > box.height * 0.25f || fling) {
                        box.animate().translationY(box.height.toFloat()).setDuration(180).withEndAction {
                            // já saiu da tela pelo arrasto: fecha sem a animação de saída da janela
                            dialog.window?.setWindowAnimations(0)
                            dialog.dismiss()
                        }.start()
                    } else {
                        box.animate().translationY(0f).scaleY(1f).setDuration(220)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                }
            }
            return true
        }
    }
}

fun Activity.bottomSheet(dark: Boolean, content: View): BottomSheet = BottomSheet(this, dark).apply { setContent(content) }

/** Diálogo de tela cheia (busca, sumário). */
fun Activity.fullScreenDialog(dark: Boolean, content: View): Dialog {
    val dialog = Dialog(
        this,
        if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
    )
    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(AppColors(dark).surface))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // entra pela direita e, ao voltar, sai deslizando para a direita
        setWindowAnimations(dev.reisarthur.papyrus.R.style.SlideWindow)
    }
    return dialog
}

/** Item de menu: ícone opcional, rótulo e ação. */
class MenuEntry(val icon: Int?, val label: String, val action: () -> Unit)

/**
 * Menu suspenso do Material 3 (surfaceContainer, cantos de 4 dp, itens de 48 dp),
 * aberto alinhado ao canto do botão que o chamou.
 */
fun Activity.showM3Menu(anchor: View, dark: Boolean, entries: List<MenuEntry>) {
    val colors = AppColors(dark)
    lateinit var popup: PopupWindow
    val list = vertical().apply {
        background = roundRect(colors.surfaceContainer, dpf(12))
        setPadding(0, dp(8), 0, dp(8))
        elevation = dpf(3)
    }
    entries.forEach { e ->
        val row = horizontal().apply {
            setPadding(dp(16), 0, dp(24), 0)
            minimumHeight = dp(48)
            ripple(colors.stateLayer(colors.onSurface))
            setOnClickListener { popup.dismiss(); e.action() }
        }
        if (e.icon != null) {
            row.addView(ImageView(this).apply {
                setImageResource(e.icon)
                setColorFilter(colors.onSurfaceVariant)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        }
        row.addView(text(e.label, 15f, colors.onSurface))
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val width = maxOf(list.measuredWidth, dp(200))
    popup = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dpf(3)
        isOutsideTouchable = true
    }
    val loc = IntArray(2)
    anchor.getLocationInWindow(loc)
    val screenH = resources.displayMetrics.heightPixels
    val opensUp = loc[1] > screenH / 2
    // cresce a partir do botão: para cima ou para baixo, conforme a posição
    popup.animationStyle = if (opensUp) dev.reisarthur.papyrus.R.style.MenuUp else dev.reisarthur.papyrus.R.style.MenuDown
    if (opensUp) {
        // botão na metade de baixo (barra inferior do leitor): o menu abre para cima
        val x = (loc[0] + anchor.width - width).coerceAtLeast(dp(8))
        val y = (loc[1] - list.measuredHeight - dp(8)).coerceAtLeast(dp(8))
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    } else {
        popup.showAsDropDown(anchor, anchor.width - width, -anchor.height + dp(4))
    }
}

/** Botões segmentados do M3: o escolhido fica em secondaryContainer com um "✓". */
fun Activity.segmented(dark: Boolean, options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val colors = AppColors(dark)
    val row = horizontal()
    val views = ArrayList<TextView>()
    fun paint(sel: Int) = views.forEachIndexed { i, v ->
        val on = i == sel
        v.text = if (on) "✓ ${options[i]}" else options[i]
        v.setTextColor(if (on) colors.onSecondaryContainer else colors.onSurface)
        val r = dpf(20)
        val radii = when {
            options.size == 1 -> floatArrayOf(r, r, r, r, r, r, r, r)
            i == 0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            i == options.lastIndex -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            else -> FloatArray(8)
        }
        v.background = roundRect(if (on) colors.secondaryContainer else Color.TRANSPARENT, 0f, colors.outline, dp(1)).apply { cornerRadii = radii }
    }
    options.forEachIndexed { i, label ->
        val v = text(label, 13.5f, colors.onSurface).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
            minHeight = dp(40)
            setOnClickListener { paint(i); onPick(i) }
        }
        views += v
        row.addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = -dp(1) })
    }
    paint(selected)
    return row
}

/** Pinta SeekBar e Switch com as cores primárias do tema. */
fun SeekBar.tintM3(colors: AppColors) {
    progressTintList = ColorStateList.valueOf(colors.primary)
    progressBackgroundTintList = ColorStateList.valueOf(colors.secondaryContainer)
    thumbTintList = ColorStateList.valueOf(colors.primary)
}

fun Switch.tintM3(colors: AppColors) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(colors.onPrimary, colors.outline))
    trackTintList = ColorStateList(states, intArrayOf(colors.primary, colors.surfaceContainerHighest))
}
