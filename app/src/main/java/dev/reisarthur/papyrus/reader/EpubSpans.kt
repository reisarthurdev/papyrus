package dev.reisarthur.papyrus.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.util.LruCache
import java.io.File

/** Troca a fonte (Literata Bold/Italic reais, em vez de negrito/itálico "falsos"). */
class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = typeface }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = typeface }
}

/** Espaço antes/depois de um parágrafo (o Android não tem margin-top/bottom nativo). */
class ParagraphSpacingSpan(private val before: Int, private val after: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanned = text as Spanned
        val spanStart = spanned.getSpanStart(this)
        val spanEnd = spanned.getSpanEnd(this)
        if (start == spanStart && before > 0) {
            fm.ascent -= before
            fm.top -= before
        }
        if (end >= spanEnd && after > 0) {
            fm.descent += after
            fm.bottom += after
        }
    }
}

/**
 * Imagem do livro ocupando uma linha própria. O tamanho é calculado na hora de montar
 * o texto; o bitmap só é decodificado quando a página é desenhada (economiza memória).
 */
class ImageBlockSpan(
    private val file: File,
    private val width: Int,
    private val height: Int,
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -height
            fm.top = -height
            fm.descent = 0
            fm.bottom = 0
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val bmp = load() ?: return
        val dst = Rect(x.toInt(), y - height, x.toInt() + width, y)
        canvas.drawBitmap(bmp, null, dst, imagePaint)
    }

    private fun load(): Bitmap? {
        val key = "${file.path}@$width"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    companion object {
        private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
