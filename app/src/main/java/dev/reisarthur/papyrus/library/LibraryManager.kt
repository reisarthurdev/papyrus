package dev.reisarthur.papyrus.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.reisarthur.papyrus.data.BookFiles
import dev.reisarthur.papyrus.data.BookFormat
import dev.reisarthur.papyrus.data.BookStatus
import dev.reisarthur.papyrus.data.Database
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.dict.DictFile
import dev.reisarthur.papyrus.dict.DictionaryImporter
import dev.reisarthur.papyrus.epub.EpubImporter
import dev.reisarthur.papyrus.pdf.PdfImporter
import dev.reisarthur.papyrus.util.Bg
import dev.reisarthur.papyrus.util.TextUtil
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Varre a pasta escolhida (via SAF), sincroniza com o banco e importa o que for novo.
 * Também importa dicionários colocados na subpasta "Dicionário".
 */
object LibraryManager {

    fun interface Listener { fun onLibraryChanged(status: String?) }

    private val listeners = LinkedHashSet<Listener>()
    private val running = AtomicBoolean(false)
    @Volatile var status: String? = null
        private set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun notify(newStatus: String?) {
        status = newStatus
        Bg.main { listeners.toList().forEach { it.onLibraryChanged(newStatus) } }
    }

    private val PLACEHOLDER_TITLE = Regex("import_\\d+")

    private data class FoundFile(val uri: Uri, val name: String, val size: Long, val parentName: String)

    fun scan(context: Context) {
        val tree = Prefs.libraryTreeUri?.let(Uri::parse) ?: return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Bg.io {
            try {
                notify("Procurando livros…")
                val files = listTree(app, tree)
                syncBooks(app, files)
                importDictionaries(app, files)
            } catch (t: Throwable) {
                notify("Não foi possível ler a pasta. Escolha-a novamente.")
                running.set(false)
                return@io
            }
            running.set(false)
            notify(null)
        }
    }

    private fun syncBooks(context: Context, files: List<FoundFile>) {
        val db = Database.instance
        val books = files.filter { it.name.endsWith(".pdf", true) || it.name.endsWith(".epub", true) }
        val byKey = db.allBooksIncludingMissing().associateBy { it.key }
        // conserta livros importados antes da correção, que ficaram com o nome da cópia temporária
        byKey.values.filter { PLACEHOLDER_TITLE.matches(it.title) }
            .forEach { db.fixTitle(it.id, it.fileName.substringBeforeLast('.')) }
        val seen = HashSet<String>()
        val toImport = ArrayList<Pair<Long, FoundFile>>()

        for (f in books) {
            val key = "${f.name}|${f.size}"
            seen += key
            val existing = byKey[key]
            when {
                existing == null -> {
                    val format = if (f.name.endsWith(".pdf", true)) BookFormat.PDF else BookFormat.EPUB
                    val id = db.insertBook(key, f.uri.toString(), f.name, format)
                    if (id > 0) toImport += id to f
                }
                existing.status == BookStatus.MISSING || existing.uri != f.uri.toString() -> {
                    db.updateBookUri(existing.id, f.uri.toString(), if (existing.sections > 0) BookStatus.READY else BookStatus.IMPORTING)
                    if (existing.sections == 0) toImport += existing.id to f
                }
                existing.status == BookStatus.IMPORTING -> toImport += existing.id to f
                // PDFs de uma versão antiga do formato: reimporta UMA vez (a marca é gravada sempre)
                existing.format == BookFormat.PDF && existing.status == BookStatus.READY &&
                    !File(BookFiles.dir(context.filesDir, existing.id), PdfImporter.FORMAT_MARKER).exists() ->
                    toImport += existing.id to f
            }
        }
        // Livros que sumiram da pasta ficam ocultos (as notas são preservadas caso o arquivo volte).
        byKey.values.filter { it.key !in seen && it.status != BookStatus.MISSING }
            .forEach { db.setStatus(it.id, BookStatus.MISSING) }
        notify(status)

        importAll(context, toImport)
    }

    /**
     * Importa vários livros ao mesmo tempo. Quantos em paralelo depende do aparelho
     * ([importThreads]); cada livro continua sendo importado inteiro por uma única thread.
     */
    private fun importAll(context: Context, toImport: List<Pair<Long, FoundFile>>) {
        if (toImport.isEmpty()) return
        val progress = ImportProgress(toImport.size, toImport.singleOrNull()?.second?.name)
        notify(progress.label())
        val threads = importThreads(context)
        val started = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(toImport.size)) { r ->
            // um pouco abaixo do normal: a interface tem prioridade, mas a importação não vai para
            // o grupo "background" do Android (que roda nos núcleos lentos e deixava tudo lento)
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 4)
                r.run()
            }.apply { name = "papyrus-import" }
        }
        try {
            toImport.mapIndexed { i, (id, file) ->
                pool.submit {
                    // as threads livres ajudam no PDF: com muitos livros na fila cada um usa 1;
                    // no fim da fila (ou com um livro só) o PDF divide as páginas entre várias
                    val remaining = toImport.size - started.getAndIncrement()
                    val parallelism = (threads / remaining.coerceIn(1, threads)).coerceAtLeast(1)
                    importBook(context, id, file.uri, file.name, parallelism) { done, total ->
                        if (progress.update(i, done, total)) notify(progress.label())
                    }
                    progress.finish(i)
                    notify(progress.label()) // a lista atualiza e o livro pronto já aparece com a capa
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Progresso somado de vários livros importando ao mesmo tempo (cada livro pesa igual). */
    private class ImportProgress(private val count: Int, private val singleName: String?) {
        private val fractions = FloatArray(count)
        private var finished = 0
        private var lastPercent = -1

        /** Devolve true quando a porcentagem inteira mudou (evita atualizar a tela à toa). */
        @Synchronized
        fun update(book: Int, done: Int, total: Int): Boolean {
            if (total <= 0) return false
            fractions[book] = (done.toFloat() / total).coerceIn(0f, 1f)
            val p = percent()
            if (p == lastPercent) return false
            lastPercent = p
            return true
        }

        @Synchronized
        fun finish(book: Int) {
            fractions[book] = 1f
            finished++
            lastPercent = percent()
        }

        private fun percent() = (fractions.sum() * 100 / count).toInt().coerceIn(0, 100)

        @Synchronized
        fun label(): String = if (singleName != null) "Importando $singleName · ${percent()}%"
        else "Importando livros · $finished de $count · ${percent()}%"
    }

    /**
     * Threads de importação: metade dos núcleos, de 1 a 3. Aparelho com pouca memória fica
     * com no máximo 2, porque PDFs grandes no pdfbox pesam bastante.
     */
    private fun importThreads(context: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val max = if (am.isLowRamDevice || am.memoryClass < 256) 2 else 3
        return (cores / 2).coerceIn(1, max)
    }

    /** Importa (ou reimporta) um livro. Roda na thread atual. */
    fun importBook(
        context: Context, id: Long, uri: Uri, name: String, parallelism: Int = 1,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val db = Database.instance
        db.setStatus(id, BookStatus.IMPORTING) // ninguém abre o livro enquanto os arquivos são refeitos
        val outDir = BookFiles.dir(context.filesDir, id)
        // a capa antiga fica até a nova ser gerada: a lista nunca mostra a capa provisória num livro já conhecido
        outDir.listFiles()?.filter { it.name != "cover.jpg" }?.forEach { it.deleteRecursively() }
        val tmp = File(context.cacheDir, "import_${id}." + name.substringAfterLast('.', "bin"))
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val result = if (name.endsWith(".pdf", true)) {
                PdfImporter(tmp, outDir, name).import(onProgress, parallelism)
            } else {
                EpubImporter(tmp, outDir, name).import(onProgress)
            }
            db.finishImport(id, result.title, result.author, result.sections)
        } catch (t: Throwable) {
            android.util.Log.e("papyrus", "Falha ao importar $name", t)
            db.setStatus(id, BookStatus.FAILED)
        } finally {
            tmp.delete()
        }
    }

    fun reimport(context: Context, id: Long) {
        val book = Database.instance.book(id) ?: return
        Database.instance.setStatus(id, BookStatus.IMPORTING)
        notify("Reimportando ${book.fileName} · 0%")
        Bg.io {
            var last = -1
            importBook(context.applicationContext, id, Uri.parse(book.uri), book.fileName, importThreads(context)) { done, total ->
                val p = if (total > 0) done * 100 / total else 0
                if (p != last) { last = p; notify("Reimportando ${book.fileName} · $p%") }
            }
            notify(null)
        }
    }

    private fun importDictionaries(context: Context, files: List<FoundFile>) {
        val dictFiles = files.filter { f ->
            val folder = TextUtil.foldKey(f.parentName)
            (folder.startsWith("dicionario") || f.name.endsWith(".ifo", true)) && DictionaryImporter.isDictionaryFile(f.name)
        }
        if (dictFiles.isEmpty()) return
        DictionaryImporter(context).import(dictFiles.map { DictFile(it.uri, it.name, it.size) }, force = false) { notify(it) }
    }

    /** Percorre a árvore do SAF recursivamente (ignora pastas ocultas). */
    private fun listTree(context: Context, tree: Uri): List<FoundFile> {
        val out = ArrayList<FoundFile>()
        val resolver = context.contentResolver
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(DocumentsContract.getTreeDocumentId(tree) to "")
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val (docId, dirName) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(id to name)
                    } else {
                        out += FoundFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), dirName)
                    }
                }
            }
        }
        return out
    }
}
