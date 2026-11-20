package dev.reisarthur.papyrus.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.reisarthur.papyrus.util.TextUtil

data class DictEntry(val headword: String, val definitionHtml: String, val source: String)

/** Banco dos dicionários importados pelo usuário. Cada arquivo importado vira uma "fonte". */
class DictionaryDb private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext, DB_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources(id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT UNIQUE NOT NULL, name TEXT NOT NULL, entries INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE entries(source_id INTEGER NOT NULL, word TEXT NOT NULL, lower TEXT NOT NULL, fold TEXT NOT NULL, def TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_lower ON entries(lower)")
        db.execSQL("CREATE INDEX idx_fold ON entries(fold)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun hasSource(key: String): Boolean = readableDatabase
        .rawQuery("SELECT 1 FROM sources WHERE key = ?", arrayOf(key)).use { it.moveToFirst() }

    fun sources(): List<Pair<String, Int>> = if (!appContext.getDatabasePath(DB_NAME).exists()) emptyList() else readableDatabase
        .rawQuery("SELECT name, entries FROM sources ORDER BY name", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    /** Sem arquivo = nada importado: responde sem criar o banco vazio. */
    fun isEmpty(): Boolean = !appContext.getDatabasePath(DB_NAME).exists() ||
        readableDatabase.rawQuery("SELECT 1 FROM entries LIMIT 1", null).use { !it.moveToFirst() }

    /** Importa entradas numa transação só (rápido mesmo com centenas de milhares de verbetes). */
    fun importSource(key: String, name: String, fill: (add: (String, String) -> Unit) -> Unit): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", "source_id IN (SELECT id FROM sources WHERE key = ?)", arrayOf(key))
            db.delete("sources", "key = ?", arrayOf(key))
            db.execSQL("INSERT INTO sources(key, name) VALUES(?, ?)", arrayOf(key, name))
            val sourceId = db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
            val stmt = db.compileStatement("INSERT INTO entries(source_id, word, lower, fold, def) VALUES(?,?,?,?,?)")
            var count = 0
            fill { word, def ->
                val w = word.trim()
                if (w.isEmpty() || def.isBlank()) return@fill
                stmt.bindLong(1, sourceId)
                stmt.bindString(2, w)
                stmt.bindString(3, w.lowercase(TextUtil.PT_BR))
                stmt.bindString(4, TextUtil.foldKey(w))
                stmt.bindString(5, def)
                stmt.executeInsert()
                count++
            }
            db.execSQL("UPDATE sources SET entries = ? WHERE id = ?", arrayOf<Any>(count, sourceId))
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM entries")
        writableDatabase.execSQL("DELETE FROM sources")
    }

    /** Verbetes com a chave sem acento [fold]. Sem banco criado ainda, nem abre (nada importado). */
    fun findByFold(fold: String): List<DictEntry> = if (isEmpty()) emptyList() else query("fold = ?", arrayOf(fold))

    private fun query(where: String, args: Array<String>): List<DictEntry> = readableDatabase.rawQuery(
        "SELECT e.word, e.def, s.name FROM entries e JOIN sources s ON s.id = e.source_id WHERE e.$where LIMIT 20",
        args
    ).use { c -> buildList { while (c.moveToNext()) add(DictEntry(c.getString(0), c.getString(1), c.getString(2))) } }

    /**
     * Versões antigas copiavam o Dicionário Aberto para este banco (~24 MB). Agora ele é lido
     * direto do APK ([BundledDictionary]); a cópia é apagada uma vez para liberar o espaço.
     */
    private fun removeLegacyBundledCopy(context: Context) {
        val prefs = context.getSharedPreferences("papyrus", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_LEGACY_REMOVED, false)) return
        runCatching {
            if (context.getDatabasePath(DB_NAME).exists() && hasSource(LEGACY_KEY)) {
                val db = writableDatabase
                db.delete("entries", "source_id IN (SELECT id FROM sources WHERE key = ?)", arrayOf(LEGACY_KEY))
                db.delete("sources", "key = ?", arrayOf(LEGACY_KEY))
                db.execSQL("VACUUM") // devolve as páginas livres ao sistema (o arquivo encolhe de verdade)
            }
        }
        prefs.edit().putBoolean(PREF_LEGACY_REMOVED, true).remove("bundledDictInstalled").apply()
    }

    companion object {
        private const val DB_NAME = "dictionary.db"
        private const val LEGACY_KEY = "builtin:dicionario-aberto"
        private const val PREF_LEGACY_REMOVED = "legacyDictRemoved"

        @Volatile private var instance: DictionaryDb? = null

        /** Dicionários importados pelo usuário (StarDict/TSV). Chamar fora da UI. */
        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also {
                    it.removeLegacyBundledCopy(context.applicationContext)
                    instance = it
                }
            }
    }
}
