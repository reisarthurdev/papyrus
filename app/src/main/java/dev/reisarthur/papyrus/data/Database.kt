package dev.reisarthur.papyrus.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Banco local (SQLite puro) com livros, destaques/notas e marcadores.
 * O dicionário fica num banco separado (ver dict/DictionaryDb).
 */
class Database private constructor(context: Context) :
    SQLiteOpenHelper(context, "library.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                uri TEXT NOT NULL,
                file_name TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL DEFAULT '',
                format INTEGER NOT NULL,
                status INTEGER NOT NULL,
                sections INTEGER NOT NULL DEFAULT 0,
                last_section INTEGER NOT NULL DEFAULT 0,
                last_offset INTEGER NOT NULL DEFAULT 0,
                progress REAL NOT NULL DEFAULT 0,
                last_opened INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                custom_title INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE annotations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                s_section INTEGER NOT NULL, s_offset INTEGER NOT NULL,
                e_section INTEGER NOT NULL, e_offset INTEGER NOT NULL,
                color INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                quote TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE bookmarks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                section INTEGER NOT NULL, pos INTEGER NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_ann_book ON annotations(book_id)")
        db.execSQL("CREATE INDEX idx_bm_book ON bookmarks(book_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: título definido pelo usuário não é sobrescrito numa reimportação
        if (oldVersion < 2) db.execSQL("ALTER TABLE books ADD COLUMN custom_title INTEGER NOT NULL DEFAULT 0")
    }

    // ---------- Livros ----------

    fun books(): List<Book> = readableDatabase
        .rawQuery("SELECT * FROM books WHERE status != ? ORDER BY last_opened DESC, added_at DESC",
            arrayOf(BookStatus.MISSING.ordinal.toString()))
        .use { c -> c.mapAll(::readBook) }

    fun allBooksIncludingMissing(): List<Book> =
        readableDatabase.rawQuery("SELECT * FROM books", null).use { c -> c.mapAll(::readBook) }

    fun book(id: Long): Book? = readableDatabase
        .rawQuery("SELECT * FROM books WHERE id = ?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) readBook(c) else null }

    fun insertBook(key: String, uri: String, fileName: String, format: BookFormat): Long =
        writableDatabase.insert("books", null, ContentValues().apply {
            put("key", key)
            put("uri", uri)
            put("file_name", fileName)
            put("title", fileName.substringBeforeLast('.'))
            put("format", format.ordinal)
            put("status", BookStatus.IMPORTING.ordinal)
            put("added_at", System.currentTimeMillis())
        })

    fun updateBookUri(id: Long, uri: String, status: BookStatus) {
        update(id, ContentValues().apply { put("uri", uri); put("status", status.ordinal) })
    }

    /** Grava o resultado da importação, mantendo o título se o usuário já o renomeou. */
    fun finishImport(id: Long, title: String, author: String, sections: Int) {
        writableDatabase.execSQL(
            "UPDATE books SET title = CASE WHEN custom_title = 1 THEN title ELSE ? END, author = ?, sections = ?, status = ? WHERE id = ?",
            arrayOf<Any>(title, author, sections, BookStatus.READY.ordinal, id),
        )
    }

    /** Corrige um título automático (não marca como escolhido pelo usuário). */
    fun fixTitle(id: Long, title: String) = update(id, ContentValues().apply { put("title", title) })

    /** Novo título escolhido pelo usuário (o arquivo já foi renomeado, então nome/URI/chave mudam junto). */
    fun renameBook(id: Long, title: String, fileName: String, uri: String, key: String) {
        update(id, ContentValues().apply {
            put("title", title)
            put("file_name", fileName)
            put("uri", uri)
            put("key", key)
            put("custom_title", 1)
        })
    }

    fun setStatus(id: Long, status: BookStatus) =
        update(id, ContentValues().apply { put("status", status.ordinal) })

    fun saveProgress(id: Long, at: Locator, progress: Float) {
        update(id, ContentValues().apply {
            put("last_section", at.section)
            put("last_offset", at.offset)
            put("progress", progress)
            put("last_opened", System.currentTimeMillis())
        })
    }

    fun deleteBook(id: Long) {
        writableDatabase.delete("books", "id = ?", arrayOf(id.toString()))
        Prefs.forgetBook(id)
    }

    private fun update(id: Long, values: ContentValues) {
        writableDatabase.update("books", values, "id = ?", arrayOf(id.toString()))
    }

    private fun readBook(c: Cursor) = Book(
        id = c.long("id"),
        key = c.str("key"),
        uri = c.str("uri"),
        fileName = c.str("file_name"),
        title = c.str("title"),
        author = c.str("author"),
        format = BookFormat.entries[c.int("format")],
        status = BookStatus.entries[c.int("status")],
        sections = c.int("sections"),
        lastSection = c.int("last_section"),
        lastOffset = c.int("last_offset"),
        progress = c.getFloat(c.getColumnIndexOrThrow("progress")),
        lastOpened = c.long("last_opened"),
        addedAt = c.long("added_at"),
    )

    // ---------- Destaques e notas ----------

    fun annotations(bookId: Long): List<Annotation> = readableDatabase
        .rawQuery(
            "SELECT * FROM annotations WHERE book_id = ? ORDER BY s_section, s_offset",
            arrayOf(bookId.toString())
        ).use { c -> c.mapAll(::readAnnotation) }

    fun insertAnnotation(a: Annotation): Long =
        writableDatabase.insert("annotations", null, annotationValues(a))

    fun updateAnnotation(a: Annotation) {
        writableDatabase.update("annotations", annotationValues(a), "id = ?", arrayOf(a.id.toString()))
    }

    fun deleteAnnotation(id: Long) {
        writableDatabase.delete("annotations", "id = ?", arrayOf(id.toString()))
    }

    private fun annotationValues(a: Annotation) = ContentValues().apply {
        put("book_id", a.bookId)
        put("s_section", a.start.section); put("s_offset", a.start.offset)
        put("e_section", a.end.section); put("e_offset", a.end.offset)
        put("color", a.color)
        put("note", a.note)
        put("quote", a.quote)
        put("created_at", a.createdAt)
    }

    private fun readAnnotation(c: Cursor) = Annotation(
        id = c.long("id"),
        bookId = c.long("book_id"),
        start = Locator(c.int("s_section"), c.int("s_offset")),
        end = Locator(c.int("e_section"), c.int("e_offset")),
        color = c.int("color"),
        note = c.str("note"),
        quote = c.str("quote"),
        createdAt = c.long("created_at"),
    )

    // ---------- Marcadores ----------

    fun bookmarks(bookId: Long): List<Bookmark> = readableDatabase
        .rawQuery(
            "SELECT * FROM bookmarks WHERE book_id = ? ORDER BY section, pos",
            arrayOf(bookId.toString())
        ).use { c ->
            c.mapAll {
                Bookmark(
                    it.long("id"), it.long("book_id"),
                    Locator(it.int("section"), it.int("pos")),
                    it.str("label"), it.long("created_at")
                )
            }
        }

    fun insertBookmark(bookId: Long, at: Locator, label: String): Long =
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("book_id", bookId)
            put("section", at.section)
            put("pos", at.offset)
            put("label", label)
            put("created_at", System.currentTimeMillis())
        })

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    companion object {
        lateinit var instance: Database
            private set

        fun init(context: Context) {
            instance = Database(context.applicationContext)
        }
    }
}

// Atalhos de leitura de cursor.
internal fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
internal fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))
internal fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
internal inline fun <T> Cursor.mapAll(read: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(read(this))
    return out
}
