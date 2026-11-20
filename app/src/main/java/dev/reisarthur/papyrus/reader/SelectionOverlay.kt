package dev.reisarthur.papyrus.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Camada transparente por cima da página: desenha a seleção (com as alças de arrastar),
 * e o resultado de busca. Quando não há seleção,
 * deixa os toques passarem para a página.
 */
class SelectionOverlay(context: Context) : View(context) {

    interface Callbacks {
        fun onHandleMoved(isStart: Boolean, x: Float, y: Float)
        fun onHandleReleased()
        fun onDismissSelection()
    }

    var callbacks: Callbacks? = null
    var accent = 0xFF1A73E8.toInt()

    private var selection: List<RectF> = emptyList()
    private var searchHit: List<RectF> = emptyList()

    private val density = resources.displayMetrics.density
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xFFF29900.toInt()
    }
    private var dragging: Boolean? = null // true = alça inicial, false = final

    val hasSelection get() = selection.isNotEmpty()

    fun setSelection(rects: List<RectF>) { selection = rects; invalidate() }
    fun setSearchHit(rects: List<RectF>) { searchHit = rects; invalidate() }

    fun clearTransient() {
        selection = emptyList()
        searchHit = emptyList()
        invalidate()
    }

    /** Topo/base da seleção na tela (para posicionar o menu flutuante). */
    fun selectionBounds(): RectF? {
        if (selection.isEmpty()) return null
        val r = RectF(selection.first())
        selection.forEach { r.union(it) }
        return r
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 3 * density
        searchHit.forEach { canvas.drawRoundRect(it, radius, radius, searchPaint) }


        if (selection.isEmpty()) return
        selPaint.color = (accent and 0x00FFFFFF) or 0x4D000000
        selection.forEach { canvas.drawRect(it, selPaint) }
        handlePaint.color = accent
        drawHandle(canvas, selection.first().left, selection.first().bottom, isStart = true)
        drawHandle(canvas, selection.last().right, selection.last().bottom, isStart = false)
    }

    /** Alça em formato de gota, igual às do Android. */
    private fun drawHandle(canvas: Canvas, x: Float, y: Float, isStart: Boolean) {
        val r = 9 * density
        val cx = if (isStart) x - r else x + r
        val cy = y + r
        canvas.drawCircle(cx, cy, r, handlePaint)
        canvas.drawRect(
            if (isStart) cx else x, y,
            if (isStart) x else cx, cy, handlePaint,
        )
    }

    private fun handleCenter(isStart: Boolean): Pair<Float, Float>? {
        if (selection.isEmpty()) return null
        val r = 9 * density
        return if (isStart) selection.first().let { (it.left - r) to (it.bottom + r) }
        else selection.last().let { (it.right + r) to (it.bottom + r) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (selection.isEmpty() && dragging == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchRadius = 28 * density
                val s = handleCenter(true)
                val en = handleCenter(false)
                dragging = when {
                    en != null && hypot(e.x - en.first, e.y - en.second) < touchRadius -> false
                    s != null && hypot(e.x - s.first, e.y - s.second) < touchRadius -> true
                    else -> null
                }
                if (dragging == null) {
                    // toque fora das alças só fecha a seleção (não vira a página sem querer)
                    callbacks?.onDismissSelection()
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { isStart ->
                // o dedo cobre a alça: mira um pouco acima, na linha de texto
                callbacks?.onHandleMoved(isStart, e.x, e.y - 22 * density)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != null) callbacks?.onHandleReleased()
                dragging = null
            }
        }
        return true
    }
}
