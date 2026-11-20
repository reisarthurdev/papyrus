package dev.reisarthur.papyrus.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Formato da fitinha de marcador (canto superior direito), compartilhado entre a página e a animação. */
object BookmarkRibbon {
    /** [drop] vai de 0 (escondida acima da tela) a 1 (no lugar). */
    fun path(screenWidth: Int, insetTop: Int, density: Float, drop: Float = 1f): Path {
        val w = density * 22
        val h = density * 40
        val right = screenWidth - density * 28
        // drop = 0: totalmente acima da tela, mesmo com notch
        val top = insetTop - (h + insetTop) * (1 - drop)
        return Path().apply {
            moveTo(right - w, top - h)          // começa acima da borda: a fita "sai" do topo
            lineTo(right, top - h)
            lineTo(right, top + h)
            lineTo(right - w / 2, top + h - w / 2.2f)
            lineTo(right - w, top + h)
            close()
        }
    }
}

/**
 * Camada que anima a fitinha ao marcar (cai do topo com um leve quique) ou desmarcar (sobe e some).
 * Fora da animação, a fitinha faz parte da própria página (e vira junto com ela).
 */
class RibbonAnimView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drop = 0f
    private var animator: ValueAnimator? = null
    var insetTop = 0

    init {
        visibility = INVISIBLE
        isClickable = false
    }

    fun animateIn(color: Int, onEnd: () -> Unit) = run(color, 0f, 1f, OvershootInterpolator(2.2f), 420, onEnd)

    fun animateOut(color: Int) = run(color, 1f, 0f, AccelerateInterpolator(1.6f), 260) {}

    private fun run(color: Int, from: Float, to: Float, interp: android.animation.TimeInterpolator, duration: Long, onEnd: () -> Unit) {
        animator?.cancel()
        paint.color = color
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { drop = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                    // some só no quadro seguinte, depois que a página já tem a fita desenhada
                    post { visibility = INVISIBLE }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(BookmarkRibbon.path(width, insetTop, resources.displayMetrics.density, drop), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
