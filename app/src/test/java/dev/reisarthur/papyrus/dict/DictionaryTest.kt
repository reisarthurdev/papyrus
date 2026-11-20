package dev.reisarthur.papyrus.dict

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** O dicionário embutido responde (palavra exata e forma flexionada). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryTest {
    @Test
    fun encontraVerbetes() {
        val d = Dictionary(RuntimeEnvironment.getApplication())
        for (w in listOf("imoral", "coragem", "Sócrates", "pensavam")) {
            val r = d.lookup(w)
            println("$w -> ${r.entries.size} ${r.entries.firstOrNull()?.headword} ${r.entries.firstOrNull()?.definitionHtml?.take(60)}")
        }
        assertTrue(d.lookup("imoral").entries.isNotEmpty())
        assertTrue(d.lookup("pensavam").entries.isNotEmpty()) // via lematizador
    }
}
