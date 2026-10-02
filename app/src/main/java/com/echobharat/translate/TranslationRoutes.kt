package com.echobharat.translate

/**
 * Which model carries which language pair.
 *
 * IndicTrans2 ships as two families, one per direction, each covering every language:
 * `en-indic` (English into any Indian language) and `indic-en` (the reverse). There is no
 * Indian-to-Indian model small enough for a phone, so Tamil → Hindi goes Tamil → English →
 * Hindi. The reader is told when that happened: a pivoted translation passes through two
 * models and deserves to be read with that in mind.
 */
object TranslationRoutes {

    const val INDIC_EN = "indic-en"
    const val EN_INDIC = "en-indic"

    /** App language code -> FLORES-200 tag the model uses. */
    val FLORES: Map<String, String> = mapOf(
        "en" to "eng_Latn",
        "hi" to "hin_Deva",
        "bn" to "ben_Beng",
        "gu" to "guj_Gujr",
        "kn" to "kan_Knda",
        "ml" to "mal_Mlym",
        "mr" to "mar_Deva",
        "or" to "ory_Orya",
        "ta" to "tam_Taml",
        "te" to "tel_Telu"
    )

    private fun isIndic(lang: String) = lang != "en" && lang in FLORES

    /** One model pass: [family] translating [source] into [target]. */
    data class Hop(val family: String, val source: String, val target: String)

    /**
     * The passes that translate [source] into [target]: none when they are the same
     * language, one for English ↔ Indian, two (via English) between Indian languages,
     * and null when either language is not one the models know.
     */
    fun route(source: String, target: String): List<Hop>? = when {
        source == target -> emptyList()
        source !in FLORES || target !in FLORES -> null
        source == "en" -> listOf(Hop(EN_INDIC, source, target))
        target == "en" -> listOf(Hop(INDIC_EN, source, target))
        else -> listOf(Hop(INDIC_EN, source, "en"), Hop(EN_INDIC, "en", target))
    }

    /**
     * The families a phone reading in [lang] needs, most important first: the one that
     * translates *into* [lang] from English, then the one that lets it understand the
     * other Indian languages. An English phone needs only `indic-en`.
     */
    fun familiesFor(lang: String): List<String> = when {
        lang == "en" -> listOf(INDIC_EN)
        isIndic(lang) -> listOf(EN_INDIC, INDIC_EN)
        else -> emptyList()
    }
}
