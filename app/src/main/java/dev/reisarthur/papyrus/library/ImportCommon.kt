package dev.reisarthur.papyrus.library

import android.graphics.Bitmap
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** O que uma importação devolve para gravar no banco. */
data class ImportResult(val title: String, val author: String, val sections: Int)

/** Salva a capa reduzida (máx. 600 px de altura) em JPEG. */
fun saveCover(src: Bitmap, file: File) {
    val maxH = 600
    val bmp = if (src.height > maxH) {
        Bitmap.createScaledBitmap(src, src.width * maxH / src.height, maxH, true)
    } else src
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    if (bmp !== src) bmp.recycle()
}

/** Leitura/escrita do sumário em JSON. */
object TocIO {
    fun save(file: File, toc: List<TocEntry>) {
        val arr = JSONArray()
        toc.forEach {
            arr.put(JSONObject().put("t", it.title).put("l", it.level).put("s", it.at.section).put("o", it.at.offset))
        }
        file.writeText(arr.toString())
    }

    fun load(file: File): List<TocEntry> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TocEntry(o.getString("t"), o.getInt("l"), Locator(o.getInt("s"), o.getInt("o")))
        }
    }
}
