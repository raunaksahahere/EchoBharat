package com.echobharat.schema

/**
 * Language codes, and the one inference the app is willing to make about an untagged text.
 */
object Languages {

    /** BCP-47 "undetermined": the text's language is not known and must not be guessed. */
    const val UNDETERMINED = "und"

    /**
     * Unicode blocks whose script belongs to exactly one language this app supports.
     *
     * Devanagari is deliberately absent — it is Hindi *and* Marathi (and Nepali, Sanskrit…)
     * — and so is Latin, which carries English and romanised Hindi alike. Labelling either
     * would feed text to a translation model for the wrong language, and a confidently
     * wrong translation is the failure this app is built to avoid.
     */
    private val UNIQUE_SCRIPTS: List<Triple<Int, Int, String>> = listOf(
        Triple(0x0980, 0x09FF, "bn"),
        Triple(0x0A80, 0x0AFF, "gu"),
        Triple(0x0B00, 0x0B7F, "or"),
        Triple(0x0B80, 0x0BFF, "ta"),
        Triple(0x0C00, 0x0C7F, "te"),
        Triple(0x0C80, 0x0CFF, "kn"),
        Triple(0x0D00, 0x0D7F, "ml")
    )

    /** Assamese letters ৰ and ৱ: Bengali script, but not Bengali. */
    private val ASSAMESE_ONLY = setOf('ৰ', 'ৱ')

    /** Share of letters that must be in one script before it names the language. */
    private const val DOMINANCE = 0.8

    /**
     * Names the language of [text] from its script alone, or returns [UNDETERMINED].
     *
     * Used only for payloads that arrive without a language tag — plain bitchat clients —
     * where the alternative used to be stamping everything as English.
     */
    fun guessFromScript(text: String): String {
        var letters = 0
        val counts = HashMap<String, Int>()
        for (ch in text) {
            if (!Character.isLetter(ch) && Character.getType(ch) != Character.NON_SPACING_MARK.toInt() &&
                Character.getType(ch) != Character.COMBINING_SPACING_MARK.toInt()
            ) continue
            letters++
            if (ch in ASSAMESE_ONLY) return UNDETERMINED
            val code = ch.code
            val lang = UNIQUE_SCRIPTS.firstOrNull { code in it.first..it.second }?.third ?: continue
            counts[lang] = (counts[lang] ?: 0) + 1
        }
        if (letters == 0) return UNDETERMINED
        val (lang, n) = counts.maxByOrNull { it.value } ?: return UNDETERMINED
        return if (n >= letters * DOMINANCE) lang else UNDETERMINED
    }

    fun isDetermined(lang: String): Boolean = lang.isNotBlank() && lang != UNDETERMINED

    /** Short label for a language chip: "HI", or "?" when it is not known. */
    fun badge(lang: String): String = if (isDetermined(lang)) lang.uppercase() else "?"
}
