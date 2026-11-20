package dev.reisarthur.papyrus.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Virada de página com efeito 3D estilo Play Livros: a folha dobra a partir do canto
 * (curva de Bézier), mostra o verso espelhado e projeta sombra na página de baixo.
 *
 * Gestos: arrastar na horizontal vira a página seguindo o dedo; toque nas bordas vira
 * com animação; toque no meio, toque longo e pinça são repassados ao [listener].
 */
class PageCurlView(context: Context) : View(context) {

    interface Provider {
        val pageCount: Int
        /** Bitmap já renderizado da página (do cache ou renderizado na hora). */
        fun bitmap(page: Int): Bitmap?
        /** Cor do fundo do tema, usada para "desbotar" o verso da folha. */
        val paperColor: Int
        /** Renderiza (fora da UI) só o trecho visível da página ampliada, para ficar nítido. */
        fun renderViewport(page: Int, zoom: Float, panX: Float, panY: Float, done: (Bitmap) -> Unit)
    }

    interface Listener {
        fun onPageChanged(page: Int)
        fun onTap(x: Float, y: Float): Boolean
        fun onLongPress(x: Float, y: Float)
        /** Entrou ou saiu do zoom de pinça (o leitor esconde seleção/destaques flutuantes). */
        fun onZoomChanged(zoomed: Boolean) {}
        /** Uma virada (arrasto ou animação) começou: o leitor esconde a camada de seleção. */
        fun onTurnStart() {}
        /** A virada terminou, tendo mudado de página ou não. */
        fun onTurnEnd() {}
    }

    var provider: Provider? = null
    var listener: Listener? = null
    var currentPage = 0
        private set

    // ---------------- Estado da curvatura ----------------

    private enum class Mode { NONE, NEXT, PREV }
    private var mode = Mode.NONE
    private var front: Bitmap? = null  // folha que está virando
    private var under: Bitmap? = null  // página revelada embaixo

    private val touch = PointF()
    private var cornerX = 0f
    private var cornerY = 0f
    private val middle = PointF()
    private val control1 = PointF()
    private val start1 = PointF()
    private val control2 = PointF()
    private val start2 = PointF()
    private val end1 = PointF()
    private val end2 = PointF()
    private val vertex1 = PointF()
    private val vertex2 = PointF()

    private val pathCurl = Path()   // região já levantada (verso + página de baixo)
    private val pathBack = Path()   // verso da folha
    private val matrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backFilterColor = 0

    // ---------------- Gestos ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var gestureHandled = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private val longPress = Runnable {
        gestureHandled = true
        listener?.onLongPress(downX, downY)
    }

    // ---------------- Zoom de pinça ----------------

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val zoomMatrix = Matrix()
    private var sharp: Bitmap? = null
    private var sharpZoom = 0f
    private var sharpPanX = 0f
    private var sharpPanY = 0f
    private var sharpPage = -1
    private var sharpRequest = 0
    private var zoomLastX = 0f
    private var zoomLastY = 0f
    private var zoomMoved = false
    private var zoomGestureHandled = false
    /**
     * Arrasto além da borda da página ampliada (px; negativo = puxando para a esquerda).
     * Se o gesto começou com a página encostada nessa borda e puxou o bastante, vira a página.
     */
    private var edgePull = 0f
    private var startedAtLeftEdge = false
    private var startedAtRightEdge = false
    private var edgeAnimator: ValueAnimator? = null
    val isZoomed get() = zoom > 1.01f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (mode != Mode.NONE) return false
            removeCallbacks(longPress)
            gestureHandled = true
            zoomGestureHandled = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val wasZoomed = isZoomed
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            val f = newZoom / zoom
            // amplia em volta do ponto entre os dedos
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            zoom = newZoom
            clampPan()
            if (wasZoomed != isZoomed) listener?.onZoomChanged(isZoomed)
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (zoom < 1.06f) resetZoom() else requestSharp()
        }
    })

    private fun clampPan() {
        panX = panX.coerceIn(width - width * zoom, 0f)
        panY = panY.coerceIn(height - height * zoom, 0f)
    }

    fun resetZoom() {
        val was = isZoomed
        zoom = 1f; panX = 0f; panY = 0f
        edgeAnimator?.cancel(); edgePull = 0f
        sharp = null
        sharpRequest++
        if (was) listener?.onZoomChanged(false)
        invalidate()
    }

    /** Pede a versão nítida do trecho visível (com um pequeno atraso para não renderizar a cada quadro). */
    private fun requestSharp() {
        removeCallbacks(sharpTask)
        postDelayed(sharpTask, 90)
    }

    private val sharpTask = Runnable {
        val p = provider ?: return@Runnable
        if (!isZoomed) return@Runnable
        val req = ++sharpRequest
        val z = zoom; val px = panX; val py = panY; val page = currentPage
        p.renderViewport(page, z, px, py) { bmp ->
            if (req != sharpRequest) return@renderViewport
            sharp = bmp; sharpZoom = z; sharpPanX = px; sharpPanY = py; sharpPage = page
            invalidate()
        }
    }

    private fun onZoomedTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoomLastX = e.x; zoomLastY = e.y
                downX = e.x; downY = e.y
                zoomMoved = false
                zoomGestureHandled = false
                edgeAnimator?.cancel(); edgePull = 0f
                // só vira a página quem JÁ estava encostado na borda ao tocar: chegar na borda
                // no meio do arrasto apenas para ali (evita virar sem querer)
                startedAtLeftEdge = panX >= -1f
                startedAtRightEdge = panX <= width - width * zoom + 1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoomGestureHandled) return true
                if (!zoomMoved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) zoomMoved = true
                if (zoomMoved) {
                    panHorizontally(e.x - zoomLastX)
                    panY += e.y - zoomLastY
                    clampPan()
                    invalidate()
                }
                zoomLastX = e.x; zoomLastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(edgePull) > width * EDGE_TURN_FRACTION && !zoomGestureHandled) {
                    // puxou a página ampliada para fora da borda: vira (e volta ao tamanho normal)
                    val next = edgePull < 0
                    resetZoom()
                    if (next) turnNext() else turnPrev()
                    return true
                }
                if (edgePull != 0f) releaseEdgePull()
                if (zoomMoved) requestSharp()
                else if (!zoomGestureHandled && listener?.onTap(e.x, e.y) != true) {
                    // tocar nas bordas ainda vira a página (e volta ao tamanho normal)
                    when {
                        e.x > width * 0.8f -> { resetZoom(); turnNext() }
                        e.x < width * 0.2f -> { resetZoom(); turnPrev() }
                    }
                }
            }
        }
        return true
    }

    /**
     * Move a página ampliada na horizontal. O que sobra depois de encostar na borda vira
     * [edgePull] (se o gesto começou ali); voltar o dedo desfaz o puxão antes de mover a página.
     */
    private fun panHorizontally(dx: Float) {
        var move = dx
        if (edgePull != 0f) {
            val pull = edgePull + move
            if (pull * edgePull <= 0f) { move = pull; edgePull = 0f } else { edgePull = pull; move = 0f }
        }
        val wanted = panX + move
        panX = wanted.coerceIn(width - width * zoom, 0f)
        val rest = wanted - panX
        if ((rest < 0 && startedAtRightEdge) || (rest > 0 && startedAtLeftEdge)) edgePull += rest
    }

    /** Soltou sem puxar o bastante: a página volta para a borda. */
    private fun releaseEdgePull() {
        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(edgePull, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { edgePull = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---------------- API pública ----------------

    fun showPage(page: Int) {
        animator?.cancel()
        resetZoom()
        mode = Mode.NONE
        currentPage = page.coerceIn(0, maxOf(0, (provider?.pageCount ?: 1) - 1))
        invalidate()
    }

    /** Página atual precisa ser redesenhada (ex.: novo destaque). */
    fun refresh() {
        if (isZoomed) { sharp = null; requestSharp() }
        invalidate()
    }

    fun turnNext(): Boolean {
        val p = provider ?: return false
        if (currentPage + 1 >= p.pageCount) return false
        finishAnimationNow()
        if (!begin(Mode.NEXT)) return false
        cornerY = height.toFloat()
        animateTouch(width - 1f, height * 0.86f, -width.toFloat(), height - 1f, complete = true, duration = 520)
        return true
    }

    fun turnPrev(): Boolean {
        if (currentPage <= 0) return false
        finishAnimationNow()
        if (!begin(Mode.PREV)) return false
        cornerY = height.toFloat()
        animateTouch(-width.toFloat(), height - 1f, width - 1f, height - 1f, complete = true, duration = 520)
        return true
    }

    val isAnimating get() = animator?.isRunning == true || mode != Mode.NONE

    // ---------------- Desenho ----------------

    override fun onDraw(canvas: Canvas) {
        val p = provider ?: return
        if (mode == Mode.NONE) {
            val base = p.bitmap(currentPage) ?: return
            if (!isZoomed) {
                canvas.drawBitmap(base, 0f, 0f, bitmapPaint)
                return
            }
            // ampliada: a página normal esticada aparece na hora; a versão nítida cobre por cima.
            // puxando além da borda, a página anda só um pouco (resistência) e o fundo aparece
            val shift = edgePull * 0.35f
            if (shift != 0f) canvas.drawColor(p.paperColor)
            zoomMatrix.setScale(zoom, zoom)
            zoomMatrix.postTranslate(panX + shift, panY)
            canvas.drawBitmap(base, zoomMatrix, bitmapPaint)
            val sh = sharp
            if (sh != null && sharpPage == currentPage && abs(sharpZoom - zoom) < 0.001f) {
                canvas.drawBitmap(sh, panX - sharpPanX + shift, panY - sharpPanY, bitmapPaint)
            }
            return
        }
        val f = front ?: return
        val u = under ?: return
        calcPoints()
        drawFrontArea(canvas, f)
        drawUnderAreaAndShadow(canvas, u)
        drawBackArea(canvas, f)
        drawEdgeShadow(canvas)
    }

    private fun drawFrontArea(canvas: Canvas, bmp: Bitmap) {
        pathCurl.reset()
        pathCurl.moveTo(start1.x, start1.y)
        pathCurl.quadTo(control1.x, control1.y, end1.x, end1.y)
        pathCurl.lineTo(touch.x, touch.y)
        pathCurl.lineTo(end2.x, end2.y)
        pathCurl.quadTo(control2.x, control2.y, start2.x, start2.y)
        pathCurl.lineTo(cornerX, cornerY)
        pathCurl.close()
        canvas.save()
        canvas.clipOutPath(pathCurl)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    private fun drawUnderAreaAndShadow(canvas: Canvas, bmp: Bitmap) {
        canvas.save()
        // toda a área que a folha deixou de cobrir mostra a página de baixo (o verso é desenhado
        // por cima depois). Recortar também por pathUnder deixava frestas com o fundo da janela.
        canvas.clipPath(pathCurl)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)

        // sombra que a folha levantada projeta na página de baixo, a partir da linha de dobra
        val mx = (start1.x + start2.x) / 2
        val my = (start1.y + start2.y) / 2
        val (nx, ny) = normalTowardCorner(start1, start2)
        val len = minOf(hypot(touch.x - cornerX, touch.y - cornerY) / 4, width / 6f)
        shadowPaint.shader = LinearGradient(mx, my, mx + nx * len, my + ny * len, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawPaint(shadowPaint)
        canvas.restore()
    }

    private fun drawBackArea(canvas: Canvas, bmp: Bitmap) {
        pathBack.reset()
        pathBack.moveTo(vertex2.x, vertex2.y)
        pathBack.lineTo(vertex1.x, vertex1.y)
        pathBack.lineTo(end1.x, end1.y)
        pathBack.lineTo(touch.x, touch.y)
        pathBack.lineTo(end2.x, end2.y)
        pathBack.close()

        // reflexão da página em torno da linha de dobra (control1 -> control2)
        val dx = cornerX - control1.x
        val dy = control2.y - cornerY
        val dis = hypot(dx, dy)
        if (dis < 0.001f) return
        val a = dx / dis
        val b = dy / dis
        matrix.setValues(floatArrayOf(1 - 2 * b * b, 2 * a * b, 0f, 2 * a * b, 1 - 2 * a * a, 0f, 0f, 0f, 1f))
        matrix.preTranslate(-control1.x, -control1.y)
        matrix.postTranslate(control1.x, control1.y)

        val paper = provider?.paperColor ?: 0xFFFFFFFF.toInt()
        if (backFilterColor != paper || backPaint.colorFilter == null) {
            backFilterColor = paper
            backPaint.colorFilter = fadeFilter(paper)
        }

        canvas.save()
        canvas.clipPath(pathCurl)
        canvas.clipPath(pathBack)
        canvas.drawBitmap(bmp, matrix, backPaint)
        // leve escurecimento perto da dobra para dar volume à curva
        val mx = (vertex1.x + vertex2.x) / 2
        val my = (vertex1.y + vertex2.y) / 2
        val (nx, ny) = normalTowardCorner(vertex1, vertex2)
        val len = hypot(touch.x - mx, touch.y - my).coerceAtLeast(1f) * 0.5f
        shadowPaint.shader = LinearGradient(mx, my, mx - nx * len, my - ny * len, 0x33000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawPaint(shadowPaint)
        canvas.restore()
    }

    /** Sombra suave da borda da folha sobre a página da frente. */
    private fun drawEdgeShadow(canvas: Canvas) {
        val (nx, ny) = normalTowardCorner(start1, start2)
        canvas.save()
        canvas.clipOutPath(pathCurl)
        val step = resources.displayMetrics.density * 2.5f
        for (i in 1..3) {
            shadowPaint.shader = null
            shadowPaint.color = 0x14000000
            canvas.save()
            canvas.translate(-nx * step * i, -ny * step * i)
            canvas.drawPath(pathBack, shadowPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Vetor unitário perpendicular a p1-p2 apontando para o canto que está sendo virado. */
    private fun normalTowardCorner(p1: PointF, p2: PointF): Pair<Float, Float> {
        var nx = -(p2.y - p1.y)
        var ny = p2.x - p1.x
        val len = hypot(nx, ny).coerceAtLeast(0.001f)
        nx /= len; ny /= len
        val mx = (p1.x + p2.x) / 2
        val my = (p1.y + p2.y) / 2
        if ((cornerX - mx) * nx + (cornerY - my) * ny < 0) { nx = -nx; ny = -ny }
        return nx to ny
    }

    /** Mistura a página com a cor do papel: o verso mostra o texto "vazado", bem apagado. */
    private fun fadeFilter(paper: Int): ColorMatrixColorFilter {
        val k = 0.22f
        val r = ((paper shr 16) and 0xFF) * (1 - k)
        val g = ((paper shr 8) and 0xFF) * (1 - k)
        val b = (paper and 0xFF) * (1 - k)
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, r,
            0f, k, 0f, 0f, g,
            0f, 0f, k, 0f, b,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    // ---------------- Geometria (dobra por curvas de Bézier) ----------------

    private fun calcPoints() {
        // evita divisões por zero quando o dedo está exatamente na linha do canto
        if (abs(touch.y - cornerY) < 1f) touch.y = if (cornerY == 0f) 1f else cornerY - 1f
        if (abs(touch.x - cornerX) < 1f) touch.x = cornerX - 1f

        computeControlPoints()
        // não deixa a folha "descolar" da lombada: limita o ponto quando a dobra sairia da tela
        if (touch.x > 0 && touch.x < width && (start1.x < 0 || start1.x > width)) {
            if (start1.x < 0) start1.x = width - start1.x
            val f1 = abs(cornerX - touch.x)
            val f2 = width * f1 / start1.x
            touch.x = abs(cornerX - f2)
            val f3 = abs(cornerX - touch.x) * abs(cornerY - touch.y) / f1
            touch.y = abs(cornerY - f3)
            if (abs(touch.y - cornerY) < 1f) touch.y = if (cornerY == 0f) 1f else cornerY - 1f
            computeControlPoints()
        }
        start2.set(cornerX, control2.y - (cornerY - control2.y) / 2)
        intersect(touch, control1, start1, start2, end1)
        intersect(touch, control2, start1, start2, end2)
        vertex1.set((start1.x + 2 * control1.x + end1.x) / 4, (2 * control1.y + start1.y + end1.y) / 4)
        vertex2.set((start2.x + 2 * control2.x + end2.x) / 4, (2 * control2.y + start2.y + end2.y) / 4)
    }

    private fun computeControlPoints() {
        middle.set((touch.x + cornerX) / 2, (touch.y + cornerY) / 2)
        control1.set(middle.x - (cornerY - middle.y) * (cornerY - middle.y) / (cornerX - middle.x), cornerY)
        control2.set(cornerX, middle.y - (cornerX - middle.x) * (cornerX - middle.x) / (cornerY - middle.y))
        start1.set(control1.x - (cornerX - control1.x) / 2, cornerY)
    }

    /** Interseção das retas p1-p2 e p3-p4 (fórmula por determinante, sem divisão por inclinação). */
    private fun intersect(p1: PointF, p2: PointF, p3: PointF, p4: PointF, out: PointF) {
        val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(d) < 1e-6f) { out.set(p2); return }
        val a = p1.x * p2.y - p1.y * p2.x
        val b = p3.x * p4.y - p3.y * p4.x
        out.set((a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d, (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d)
    }

    // ---------------- Toque ----------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.NONE && !dragging) scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress || e.pointerCount > 1) {
            removeCallbacks(longPress)
            gestureHandled = true
            zoomGestureHandled = true
            return true
        }
        if (isZoomed) return onZoomedTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animator?.isRunning == true) finishAnimationNow()
                downX = e.x; downY = e.y
                dragging = false
                gestureHandled = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                postDelayed(longPress, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (gestureHandled) return true
                val dx = e.x - downX
                if (!dragging && (abs(dx) > slop * 1.5f || abs(e.y - downY) > slop * 1.5f)) {
                    removeCallbacks(longPress)
                    if (abs(dx) > abs(e.y - downY)) {
                        dragging = startDrag(dx)
                    }
                    if (!dragging) gestureHandled = true
                }
                if (dragging) updateDrag(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    releaseDrag(velocity?.xVelocity ?: 0f, e.x - downX)
                } else if (!gestureHandled) {
                    if (listener?.onTap(e.x, e.y) != true) handleTap(e.x)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) releaseDrag(0f, 0f, forceCancel = true)
                dragging = false
            }
        }
        return true
    }

    private fun handleTap(x: Float) {
        when {
            x > width * 0.68f -> turnNext()
            x < width * 0.32f -> turnPrev()
        }
    }

    private var dragCornerTop = false
    private var dragMiddle = false

    private fun startDrag(dx: Float): Boolean {
        val p = provider ?: return false
        val wanted = if (dx < 0) Mode.NEXT else Mode.PREV
        if (wanted == Mode.NEXT && currentPage + 1 >= p.pageCount) return false
        if (wanted == Mode.PREV && currentPage <= 0) return false
        if (!begin(wanted)) return false
        dragCornerTop = downY < height / 3f
        dragMiddle = !dragCornerTop && downY < height * 2 / 3f
        cornerX = width.toFloat()
        cornerY = if (dragCornerTop) 0f else height.toFloat()
        return true
    }

    private fun updateDrag(x: Float, y: Float) {
        val dx = x - downX
        val tx = if (mode == Mode.NEXT) width - 1f + dx * 1.5f else -width + dx * 1.5f
        touch.x = tx.coerceIn(-width.toFloat(), width - 1f)
        touch.y = when {
            dragMiddle -> height - 1f
            else -> y.coerceIn(1f, height - 1f)
        }
        invalidate()
    }

    private fun releaseDrag(vx: Float, dx: Float, forceCancel: Boolean = false) {
        val fling = abs(vx) > 900
        val complete = !forceCancel && if (mode == Mode.NEXT) {
            (fling && vx < 0) || (!fling && dx < -width * 0.15f)
        } else {
            (fling && vx > 0) || (!fling && dx > width * 0.15f)
        }
        val endY = if (cornerY == 0f) 1f else height - 1f
        val targetX = when {
            mode == Mode.NEXT && complete -> -width.toFloat()
            mode == Mode.NEXT -> width - 1f
            complete -> width - 1f
            else -> -width.toFloat()
        }
        animateTouch(touch.x, touch.y, targetX, endY, complete, duration = 380)
    }

    // ---------------- Animação ----------------

    private fun begin(newMode: Mode): Boolean {
        val p = provider ?: return false
        if (width == 0 || height == 0) return false
        if (isZoomed) resetZoom()
        val f: Bitmap?
        val u: Bitmap?
        if (newMode == Mode.NEXT) {
            f = p.bitmap(currentPage); u = p.bitmap(currentPage + 1)
        } else {
            f = p.bitmap(currentPage - 1); u = p.bitmap(currentPage)
        }
        if (f == null || u == null) return false
        front = f
        under = u
        mode = newMode
        listener?.onTurnStart()
        cornerX = width.toFloat()
        cornerY = height.toFloat()
        return true
    }

    private fun animateTouch(fromX: Float, fromY: Float, toX: Float, toY: Float, complete: Boolean, duration: Long) {
        animator?.cancel()
        val animMode = mode
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val t = it.animatedValue as Float
                touch.x = fromX + (toX - fromX) * t
                // pequena "barriga" no meio do movimento deixa a curva mais natural
                touch.y = fromY + (toY - fromY) * t - (if (cornerY == 0f) -1 else 1) * height * 0.06f * (4 * t * (1 - t))
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    endTurn(animMode, complete)
                }
            })
            start()
        }
    }

    private fun endTurn(turnMode: Mode, complete: Boolean) {
        if (mode != turnMode) return
        mode = Mode.NONE
        front = null
        under = null
        if (complete) {
            currentPage += if (turnMode == Mode.NEXT) 1 else -1
            listener?.onPageChanged(currentPage)
        }
        listener?.onTurnEnd()
        invalidate()
    }

    /** Termina na hora uma animação em andamento (ex.: toques rápidos seguidos). */
    private fun finishAnimationNow() {
        val a = animator ?: return
        if (a.isRunning) a.end()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        removeCallbacks(longPress)
        removeCallbacks(sharpTask)
        velocity?.recycle()
        velocity = null
    }

    private companion object {
        const val MAX_ZOOM = 3f
        /** Quanto (fração da largura) puxar além da borda, com zoom, para virar a página. */
        private const val EDGE_TURN_FRACTION = 0.18f
    }
}
