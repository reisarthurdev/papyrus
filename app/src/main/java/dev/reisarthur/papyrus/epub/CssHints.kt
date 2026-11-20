package dev.reisarthur.papyrus.epub

/**
 * Lê só o que importa do CSS do livro: quais classes são itálico, negrito,
 * centralizadas, alinhadas à direita ou versalete. O resto do CSS é ignorado
 * de propósito: a tipografia é sempre a do app (Literata, margens, espaçamento).
 */
class CssHints {
    private val italic = HashSet<String>()
    private val bold = HashSet<String>()
    private val center = HashSet<String>()
    private val right = HashSet<String>()
    private val smallCaps = HashSet<String>()

    fun addStylesheet(css: String) {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        RULE.findAll(clean).forEach { m ->
            val decl = m.groupValues[2].lowercase()
            val classes = m.groupValues[1].split(',').mapNotNull { sel ->
                // aceita ".x", "p.x", "span.x" (o último seletor simples de cada lista)
                sel.trim().split(Regex("\\s+")).lastOrNull()?.substringAfter('.', "")?.takeIf { it.isNotEmpty() }
            }
            if (classes.isEmpty()) return@forEach
            if (decl.contains("italic") || decl.contains("oblique")) italic += classes
            if (BOLD_DECL.containsMatchIn(decl)) bold += classes
            if (ALIGN_CENTER.containsMatchIn(decl)) center += classes
            if (ALIGN_RIGHT.containsMatchIn(decl)) right += classes
            if (decl.contains("small-caps")) smallCaps += classes
        }
    }

    /** Estilo inline (bits de [Style]) para um elemento. */
    fun inlineStyle(classAttr: String?, styleAttr: String?): Int {
        var s = 0
        classAttr?.split(Regex("\\s+"))?.forEach { c ->
            if (c in italic) s = s or Style.ITALIC
            if (c in bold) s = s or Style.BOLD
            if (c in smallCaps) s = s or Style.SMALL_CAPS
        }
        styleAttr?.lowercase()?.let { st ->
            if (st.contains("italic")) s = s or Style.ITALIC
            if (BOLD_DECL.containsMatchIn(st)) s = s or Style.BOLD
            if (st.contains("small-caps")) s = s or Style.SMALL_CAPS
        }
        return s
    }

    /** Alinhamento indicado por classe, atributo align ou style. */
    fun alignment(classAttr: String?, styleAttr: String?, alignAttr: String?): Block.Kind? {
        val st = styleAttr?.lowercase().orEmpty()
        val al = alignAttr?.lowercase().orEmpty()
        val classes = classAttr?.split(Regex("\\s+")).orEmpty()
        return when {
            al == "center" || ALIGN_CENTER.containsMatchIn(st) || classes.any { it in center } -> Block.Kind.CENTER
            al == "right" || ALIGN_RIGHT.containsMatchIn(st) || classes.any { it in right } -> Block.Kind.RIGHT
            else -> null
        }
    }

    private companion object {
        val RULE = Regex("([^{}@]+)\\{([^}]*)\\}")
        // precisa ser exatamente text-align: (evita "background-position: center" etc.)
        val ALIGN_CENTER = Regex("text-align\\s*:\\s*center")
        val ALIGN_RIGHT = Regex("text-align\\s*:\\s*(right|end)")
        val BOLD_DECL = Regex("font-weight\\s*:\\s*(bold|bolder|[6-9]00)")
    }
}
