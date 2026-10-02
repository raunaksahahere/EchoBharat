package com.echobharat.translate

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * The text processing IndicTrans2 was trained with, ported from AI4Bharat's
 * `IndicTransToolkit.IndicProcessor` (and the indic-nlp and sacremoses pieces it calls) so
 * no Python runs on the phone.
 *
 * The model never sees raw text. On the way in, punctuation is normalised, long numbers,
 * URLs and e-mail addresses are swapped for `<ID1>`-style placeholders the model copies
 * through untouched, English is Moses-tokenised, and every Indic script is tokenised and
 * then **transliterated into Devanagari** — the model was trained on one unified script.
 * On the way out the placeholders are restored, and Indic output is transliterated back
 * into the target script and detokenised. Skip the transliteration and Tamil, Bengali or
 * Telugu input reaches the model as a script it has barely seen; that was invisible while
 * translation was Hindi-only, because Hindi already is Devanagari.
 *
 * Checked against the reference implementation by `IndicTransTextTest`, whose golden cases
 * come from `model-export/make_mt_golden.py`. Two reference behaviours are deliberately not
 * reproduced, both bugs that corrupt text rather than normalise it:
 *  - its Devanagari visarga rule is double-escaped and rewrites "10:" as the literal
 *    string `1\1ः`; no visarga substitution is done here;
 *  - nothing else differs.
 */
object IndicTransText {

    /** Preprocessed model input, and what the placeholders in it stand for. */
    data class Prepared(val text: String, val placeholders: Map<String, String>)

    /** ISO code -> first code point of its Unicode block. */
    private val SCRIPT_BASE = mapOf(
        "hi" to 0x0900, "mr" to 0x0900,
        "bn" to 0x0980,
        "gu" to 0x0A80,
        "or" to 0x0B00,
        "ta" to 0x0B80,
        "te" to 0x0C00,
        "kn" to 0x0C80,
        "ml" to 0x0D00
    )

    fun isSupported(lang: String) = lang == "en" || lang in SCRIPT_BASE

    fun preprocess(text: String, srcLang: String): Prepared {
        var sent = punctuationNormalize(text)
        sent = normalizeDigits(sent)
        val (wrapped, placeholders) = wrapPlaceholders(sent)
        sent = wrapped

        val processed = if (srcLang == "en") {
            Moses.tokenize(Moses.normalizePunctuation(sent.trim()))
        } else {
            val tokens = trivialTokenize(IndicNormalizer.normalize(sent.trim(), srcLang))
            transliterate(tokens, srcLang, "hi").replace(" ् ", "्")
        }
        return Prepared(processed.trim(), placeholders)
    }

    fun postprocess(decoded: String, tgtLang: String, placeholders: Map<String, String>): String {
        var sent = decoded
        if (tgtLang == "or") sent = sent.replace("ଯ଼", "ୟ")
        for ((k, v) in placeholders) sent = sent.replace(k, v)
        return if (tgtLang == "en") {
            Moses.detokenize(sent.split(" "))
        } else {
            trivialDetokenize(transliterate(sent, "hi", tgtLang))
        }
    }

    // ---- IndicProcessor._punc_norm -----------------------------------------------------

    private val PUNC_REPLACEMENTS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("\r") to { _ -> "" },
        Regex("\\(\\s*") to { _ -> "(" },
        Regex("\\s*\\)") to { _ -> ")" },
        Regex("\\s:\\s?") to { _ -> ":" },
        Regex("\\s;\\s?") to { _ -> ";" },
        Regex("[`´‘‚’]") to { _ -> "'" },
        Regex("[„“”«»]") to { _ -> "\"" },
        Regex("[–—]") to { _ -> "-" },
        Regex(" %") to { _ -> "%" },
        Regex(" [?!;]") to { m -> m.value.trim() }
    )
    private val MULTISPACE = Regex("[ ]{2,}")
    private val END_BRACKET_SPACE_PUNC = Regex("\\) ([.!:?;,])")
    private val DIGIT_SPACE_PERCENT = Regex("(\\d) %")
    private val DOUBLE_QUOT_PUNC = Regex("\"([,.]+)")
    private val DIGIT_NBSP_DIGIT = Regex("(\\d)\u00A0(\\d)")

    internal fun punctuationNormalize(text: String): String {
        var t = text
        for ((re, repl) in PUNC_REPLACEMENTS) t = re.replace(t, repl)
        t = MULTISPACE.replace(t, " ")
        t = END_BRACKET_SPACE_PUNC.replace(t, ")$1")
        t = DIGIT_SPACE_PERCENT.replace(t, "$1%")
        t = DOUBLE_QUOT_PUNC.replace(t, "$1\"")
        t = DIGIT_NBSP_DIGIT.replace(t, "$1.$2")
        return t.trim()
    }

    // ---- IndicProcessor._normalize -----------------------------------------------------

    /** Zero of every script's digits the reference folds to ASCII. */
    private val DIGIT_ZEROS = intArrayOf(
        0x09E6, 0x0AE6, 0x0CE6, 0x0966, 0x0660, 0xABF0, 0x0B66, 0x0A66, 0x1C50, 0x06F0, 0x0C66
    )

    internal fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val zero = DIGIT_ZEROS.firstOrNull { ch.code in it..it + 9 }
            // U+0C66 (Telugu zero) is absent from the reference table; 1-9 are present.
            if (zero != null && !(zero == 0x0C66 && ch.code == 0x0C66)) {
                sb.append('0' + (ch.code - zero))
            } else sb.append(ch)
        }
        return sb.toString()
    }

    // Android's ICU patterns already use Unicode classes and reject Java's U flag.
    // Spell the classes out so Android and the desktop reference use the same contract.
    private const val UNICODE_SPACE = "\\p{Z}\\t\\n\\u000B\\f\\r\\u0085"
    private fun unicodePattern(pattern: String): Pattern = Pattern.compile(
        pattern.replace("\\w", "[\\p{IsAlphabetic}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D]")
            .replace("\\d", "\\p{Nd}")
            .replace("\\s", "[$UNICODE_SPACE]")
    )

    private val EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}")

    // The reference writes the last group as (?:[...]+)+, which matches the same strings
    // but backtracks exponentially on a long miss; the single + is equivalent.
    private val URL = unicodePattern(
        "\\b(?<![\\w/.])(?:(?:https?|ftp)://)?(?:(?:[\\w-]+\\.)+(?!\\.))[\\w/\\-?#&=%.]+(?!\\.\\w+)\\b"
    )
    private val NUMERAL = unicodePattern(
        "(~?\\d+\\.?\\d*\\s?%?\\s?-?\\s?~?\\d+\\.?\\d*\\s?%|~?\\d+%|\\d+[-/.,:']\\d+[-/.,:'+]\\d+(?:\\.\\d+)?|\\d+[-/.:'+]\\d+(?:\\.\\d+)?)"
    )
    private val OTHER = unicodePattern("[A-Za-z0-9]*[#|@]\\w+")

    /** Forms the model has been seen to mangle a placeholder into, per the reference. */
    private val INDIC_FAILURE_CASES = listOf(
        "آی ڈی ", "ꯑꯥꯏꯗꯤ", "आईडी", "आई . डी . ", "आई . डी .", "आई. डी. ", "आई. डी.",
        "आय. डी. ", "आय. डी.", "आय . डी . ",
        // The reference list is missing a comma here, so these two are one string.
        "आय . डी .आइ . डी . ",
        "आइ . डी .", "आइ. डी. ", "आइ. डी.", "ऐटि", "آئی ڈی ", "ᱟᱭᱰᱤ ᱾", "आयडी", "ऐडि", "आइडि", "ᱟᱭᱰᱤ"
    )

    internal fun wrapPlaceholders(text: String): Pair<String, Map<String, String>> {
        var t = text
        var serial = 1
        val map = LinkedHashMap<String, String>()
        for (pattern in listOf(EMAIL, URL, NUMERAL, OTHER)) {
            val matches = LinkedHashSet<String>()
            val m: Matcher = pattern.matcher(t)
            while (m.find()) matches.add(m.group())
            for (match in matches) {
                if (pattern === URL && match.replace(".", "").length < 4) continue
                if (pattern === NUMERAL &&
                    match.replace(" ", "").replace(".", "").replace(":", "").length < 4
                ) continue

                for (id in listOf("ID", "id")) {
                    map["<$id$serial>"] = match
                    map["< $id$serial >"] = match
                    map["[$id$serial]"] = match
                    map["[ $id$serial ]"] = match
                    map["[$id $serial]"] = match
                    map["<$id$serial]"] = match
                    map["< $id$serial]"] = match
                    map["<$id$serial ]"] = match
                }
                for (c in INDIC_FAILURE_CASES) {
                    map["<$c$serial>"] = match
                    map["< $c$serial >"] = match
                    map["< $c $serial >"] = match
                    map["<$c $serial]"] = match
                    map["< $c $serial ]"] = match
                    map["[$c$serial]"] = match
                    map["[$c $serial]"] = match
                    map["[ $c$serial ]"] = match
                    map["[ $c $serial ]"] = match
                    map["$c $serial"] = match
                    map["$c$serial"] = match
                }
                t = t.replace(match, "<ID$serial>")
                serial++
            }
        }
        t = t.replace(Regex("\\s+"), " ").replace(">/", ">").replace("]/", "]")
        return t to map
    }

    // ---- indicnlp: trivial tokenizer / detokenizer -------------------------------------

    private const val ASCII_PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val INDIC_PUNCT: Set<Char> =
        (ASCII_PUNCT + "\u0964\u0965\uAAF1\uAAF0\uABEB\uABEC\uABED\uABEE\uABEF\u1C7E\u1C7F").toSet()
    private val NUM_SEQ = Regex("([0-9]+ [,.:/] )+[0-9]+")

    internal fun trivialTokenize(text: String): String {
        val sb = StringBuilder()
        for (ch in text.replace('\t', ' ')) {
            if (ch in INDIC_PUNCT) sb.append(' ').append(ch).append(' ') else sb.append(ch)
        }
        val s = sb.toString().replace(Regex("[ ]+"), " ").trim(' ')
        // Numbers and dates are not split: "12 , 000" goes back to "12,000".
        return NUM_SEQ.replace(s) { it.value.replace(" ", "") }
    }

    private val LEFT_ATTACH = Regex("[ ]([!%)\\]},.:;>?\u0964\u0965])")
    private val RIGHT_ATTACH = Regex("([#$(\\[{<@])[ ]")
    private val LR_ATTACH = Regex("[ ]([-/\\\\])[ ]")

    internal fun trivialDetokenize(text: String): String {
        var s = text
        // The reference only rejoins a number sequence when text precedes it; kept as is.
        val parts = StringBuilder()
        var prev = 0
        for (m in NUM_SEQ.findAll(s)) {
            if (m.range.first > prev) {
                parts.append(s, prev, m.range.first)
                parts.append(m.value.replace(" ", ""))
                prev = m.range.last + 1
            }
        }
        parts.append(s.substring(prev))
        s = parts.toString()

        s = LR_ATTACH.replace(s, "$1")
        s = LEFT_ATTACH.replace(s, "$1")
        s = RIGHT_ATTACH.replace(s, "$1")

        for (punc in charArrayOf('\'', '"', '`')) {
            var count = 0
            val out = StringBuilder()
            for (c in s) {
                if (c == punc) {
                    out.append(if (count % 2 == 0) "@RA" else "@LA")
                    count++
                } else out.append(c)
            }
            s = out.toString()
                .replace("@RA ", punc.toString())
                .replace(" @LA", punc.toString())
                .replace("@RA", punc.toString())
                .replace("@LA", punc.toString())
        }
        return s
    }

    // ---- indicnlp: UnicodeIndicTransliterator ------------------------------------------

    /**
     * Maps between Brahmic scripts by Unicode block offset — the blocks are laid out in
     * parallel, which is what makes the model's single-script training possible.
     */
    internal fun transliterate(text: String, from: String, to: String): String {
        val fromBase = SCRIPT_BASE[from] ?: return text
        val toBase = SCRIPT_BASE[to] ?: return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            var offset = c.code - fromBase
            if (offset in 0..0x6F && c != '\u0964' && c != '\u0965') {
                if (to == "ta") offset = tamilOffset(offset)
                sb.append((toBase + offset).toChar())
            } else sb.append(c)
        }
        return sb.toString()
    }

    /** Tamil has no aspirated or voiced stops; fold them onto the letter Tamil does have. */
    private fun tamilOffset(offset: Int): Int {
        var o = offset
        if (o in 0x15..0x28 && o != 0x1C && !((o - 0x15) % 5 == 0 || (o - 0x15) % 5 == 4)) {
            o = 0x15 + 5 * ((o - 0x15) / 5)
        }
        if (o == 0x2B || o == 0x2C || o == 0x2D) o = 0x2A
        if (o == 0x36) o = 0x37
        return o
    }

    // ---- indicnlp: IndicNormalizer (factory defaults) ----------------------------------

    internal object IndicNormalizer {
        fun normalize(text: String, lang: String): String {
            var t = if (lang == "ml") malayalamChillus(text) else text
            t = base(t)
            t = when (lang) {
                "hi", "mr" -> devanagari(t)
                "bn" -> bengali(t)
                "gu" -> gujarati(t)
                "or" -> oriya(t)
                "ta" -> tamil(t)
                "te" -> telugu(t)
                "kn" -> kannada(t)
                "ml" -> malayalam(t)
                else -> t
            }
            return t
        }

        private fun String.rep(vararg pairs: Pair<String, String>): String {
            var t = this
            for ((a, b) in pairs) t = t.replace(a, b)
            return t
        }

        private fun base(text: String) = text.rep(
            "\uFEFF" to "", "\uFFFE" to "", "\u2060" to "", "\u00AD" to "",
            "\u200B" to " ", "\u00A0" to " ", "\u200C" to "", "\u200D" to "",
            // _normalize_punctuations
            "\uFEFF" to "", "„" to "\"", "“" to "\"", "”" to "\"", "–" to "-", "—" to " - ",
            "´" to "'", "‘" to "'", "‚" to "'", "’" to "'", "''" to "\"", "´´" to "\"", "…" to "..."
        )

        private const val DN = "\u093C"
        private fun devanagari(t: String) = t.rep(
            "\u0972" to "\u090f",
            "\u0929" to "\u0928$DN", "\u0931" to "\u0930$DN", "\u0934" to "\u0933$DN",
            "\u0958" to "\u0915$DN", "\u0959" to "\u0916$DN", "\u095a" to "\u0917$DN",
            "\u095b" to "\u091c$DN", "\u095c" to "\u0921$DN", "\u095d" to "\u0922$DN",
            "\u095e" to "\u092b$DN", "\u095f" to "\u092f$DN",
            "|" to "\u0964"
        )

        private fun bengali(t: String) = t.rep(
            "\u09dc" to "\u09a1\u09BC", "\u09dd" to "\u09a2\u09BC", "\u09df" to "\u09af\u09BC",
            "\u09e4" to "\u0964", "\u09e5" to "\u0965", "|" to "\u0964", "\u09f7" to "\u0964",
            "\u09c7\u09be" to "\u09cb", "\u09c7\u09d7" to "\u09cc"
        )

        private fun gujarati(t: String) = t.rep("\u0ae4" to "\u0964", "\u0ae5" to "\u0965")

        private fun oriya(t: String) = t.rep(
            "\u0b05\u0b3e" to "\u0b06", "\u0b0f\u0b57" to "\u0b10", "\u0b13\u0b57" to "\u0b14",
            "\u0b5c" to "\u0b21\u0B3C", "\u0b5d" to "\u0b22\u0B3C",
            "\u0b64" to "\u0964", "\u0b65" to "\u0965", "\u0b7c" to "\u0964",
            "\u0b35" to "\u0b2c",
            "\u0b47\u0b56" to "\u0b58", "\u0b47\u0b3e" to "\u0b4b", "\u0b47\u0b57" to "\u0b4c"
        )

        private fun tamil(t: String) = t.rep(
            "\u0be4" to "\u0964", "\u0be5" to "\u0965",
            "\u0b92\u0bd7" to "\u0b94", "\u0bc6\u0bbe" to "\u0bca",
            "\u0bc7\u0bbe" to "\u0bcb", "\u0bc6\u0bd7" to "\u0bcc"
        )

        private fun telugu(t: String) = t.rep(
            "\u0c64" to "\u0964", "\u0c65" to "\u0965", "\u0c46\u0c56" to "\u0c48"
        )

        private fun kannada(t: String) = t.rep(
            "\u0ce4" to "\u0964", "\u0ce5" to "\u0965",
            "\u0cbf\u0cd5" to "\u0cc0", "\u0cc6\u0cd5" to "\u0cc7", "\u0cc6\u0cd6" to "\u0cc8",
            "\u0cc6\u0cc2" to "\u0cca", "\u0cca\u0cd5" to "\u0ccb"
        )

        /** Runs before the base normaliser strips ZWJ, which would destroy the chillu. */
        private fun malayalamChillus(t: String) = t.rep(
            "\u0d23\u0d4d\u200d" to "\u0d7a", "\u0d28\u0d4d\u200d" to "\u0d7b",
            "\u0d30\u0d4d\u200d" to "\u0d7c", "\u0d32\u0d4d\u200d" to "\u0d7d",
            "\u0d33\u0d4d\u200d" to "\u0d7e", "\u0d15\u0d4d\u200d" to "\u0d7f"
        )

        private fun malayalam(t: String) = t.rep(
            "\u0d64" to "\u0964", "\u0d65" to "\u0965",
            "\u0d46\u0d3e" to "\u0d4a", "\u0d47\u0d3e" to "\u0d4b",
            "\u0d46\u0d57" to "\u0d4c", "\u0d57" to "\u0d4c"
        )
    }

    // ---- sacremoses (English) ----------------------------------------------------------

    internal object Moses {

        private fun isAlpha(c: Int) = Character.isAlphabetic(c) || c == 0x094D || c == 0x093C

        private val NORMALIZE: List<Pair<Regex, String>> = listOf(
            // EXTRA_WHITESPACE
            Regex("\r") to "", Regex("\\(") to " (", Regex("\\)") to ") ", Regex(" +") to " ",
            Regex("\\) ([.!:?;,])") to ")$1", Regex("\\( ") to "(", Regex(" \\)") to ")",
            Regex("(\\d) %") to "$1%", Regex(" :") to ":", Regex(" ;") to ";",
            // NORMALIZE_UNICODE_IF_NOT_PENN (penn=True inserts it)
            Regex("`") to "'", Regex("''") to " \" ",
            // NORMALIZE_UNICODE
            Regex("„") to "\"", Regex("“") to "\"", Regex("”") to "\"", Regex("–") to "-",
            Regex("—") to " - ", Regex(" +") to " ", Regex("´") to "'",
            Regex("([a-zA-Z])‘([a-zA-Z])") to "$1'$2", Regex("([a-zA-Z])’([a-zA-Z])") to "$1'$2",
            Regex("‘") to "'", Regex("‚") to "'", Regex("’") to "'", Regex("''") to "\"",
            Regex("´´") to "\"", Regex("…") to "...",
            // FRENCH_QUOTES
            Regex("\u00A0«\u00A0") to "\"", Regex("«\u00A0") to "\"", Regex("«") to "\"",
            Regex("\u00A0»\u00A0") to "\"", Regex("\u00A0»") to "\"", Regex("»") to "\"",
            // HANDLE_PSEUDO_SPACES
            Regex("\u00A0%") to "%", Regex("nº\u00A0") to "nº ", Regex("\u00A0:") to ":",
            Regex("\u00A0ºC") to " ºC", Regex("\u00A0cm") to " cm", Regex("\u00A0\\?") to "?",
            Regex("\u00A0!") to "!", Regex("\u00A0;") to ";", Regex(",\u00A0") to ", ",
            Regex(" +") to " ",
            // EN_QUOTATION_FOLLOWED_BY_COMMA, then OTHER (number separators)
            Regex("\"([,.]+)") to "$1\"",
            Regex("(\\d)\u00A0(\\d)") to "$1.$2"
        )

        fun normalizePunctuation(text: String): String {
            var t = text
            for ((re, repl) in NORMALIZE) t = re.replace(t, repl)
            return t.trim()
        }

        private val NONBREAKING = setOf(
            "A", "Adj", "Adm", "Adv", "Apr", "Asst", "Aug", "B", "Bart", "Bldg", "Brig", "Bros",
            "C", "Capt", "Cmdr", "Col", "Comdr", "Con", "Corp", "Cpl", "D", "DR", "Dec", "Dr",
            "Drs", "E", "Ens", "F", "Feb", "G", "Gen", "Gov", "H", "Hon", "Hosp", "Hr", "I",
            "Insp", "J", "Jan", "Jul", "Jun", "K", "L", "Lt", "M", "MM", "MR", "MRS", "MS",
            "Maj", "Mar", "Messrs", "Mlle", "Mme", "Mr", "Mrs", "Ms", "Msgr", "N", "Nos", "Nov",
            "Nr", "O", "Oct", "Op", "Ord", "P", "Pfc", "Ph", "Prof", "Pvt", "Q", "R", "Rep",
            "Reps", "Res", "Rev", "Rs", "Rt", "S", "Sen", "Sens", "Sep", "Sfc", "Sgt", "Sr",
            "St", "Supt", "Surg", "T", "U", "V", "W", "X", "Y", "Z", "e.g", "i.e", "rev", "v", "vs"
        )
        private val NUMERIC_ONLY = setOf("Art", "No", "pp")

        // Perl's IsAlpha / IsAlnum, plus the virama and nukta sacremoses adds to them.
        private const val ALPHA = "\\p{IsAlphabetic}\\u094D\\u093C"
        private const val ALNUM = "$ALPHA\\p{Nd}"

        private val PAD_NOT_ALNUM = Regex("([^$ALNUM$UNICODE_SPACE.'`,\\-])")
        private val APOSTROPHE_RULES = listOf(
            Regex("([^$ALPHA])'([^$ALPHA])") to "$1 ' $2",
            Regex("([^$ALPHA\\p{N}])'([$ALPHA])") to "$1 ' $2",
            Regex("([$ALPHA])'([^$ALPHA])") to "$1 ' $2",
            Regex("([$ALPHA])'([$ALPHA])") to "$1 '$2",
            Regex("(\\p{N})'(s)") to "$1 '$2"
        )

        fun tokenize(input: String): String {
            var text = input.replace(Regex("\\s+"), " ").replace(Regex("[\\u0000-\\u001f]"), "").trim()
            text = PAD_NOT_ALNUM.replace(text, " $1 ")

            // Multi-dots stay together.
            text = Regex("\\.{2,}").replace(text) { m ->
                val marker = " " + "DOT".repeat(m.value.length) + "MULTI"
                if (m.range.last + 1 < text.length) "$marker " else marker
            }

            // Separate "," except inside numbers (5,300).
            text = Regex("([^\\p{N}]),").replace(text, "$1 , ")
            text = Regex(",([^\\p{N}])").replace(text, " , $1")
            text = Regex("(\\p{N}),$").replace(text, "$1 , ")

            // English apostrophes: "don't" -> "don 't", "'quoted'" -> "' quoted '".
            for ((re, repl) in APOSTROPHE_RULES) text = re.replace(text, repl)

            text = nonbreakingPrefixes(text)
            text = text.replace(Regex("\\s+"), " ").trim()
            text = Regex("\\.' ?$").replace(text, " . ' ")

            text = Regex("(?:DOT)+MULTI").replace(text) { ".".repeat((it.value.length - 5) / 3) }
            return text.split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun nonbreakingPrefixes(text: String): String {
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            for (i in tokens.indices) {
                val token = tokens[i]
                if (!(token.length >= 2 && token.endsWith(".") && token.none { it.isWhitespace() })) continue
                val prefix = token.dropLast(1)
                val next = tokens.getOrNull(i + 1)
                val keep = ('.' in prefix && prefix.any { isAlpha(it.code) }) ||
                    (prefix in NONBREAKING && prefix !in NUMERIC_ONLY) ||
                    (i != tokens.lastIndex && !next.isNullOrEmpty() && Character.isLowerCase(next[0]))
                val numericKeep = prefix in NUMERIC_ONLY && next != null && next.first().isAsciiDigit()
                if (!keep && !numericKeep) tokens[i] = "$prefix ."
            }
            return tokens.joinToString(" ")
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private fun isCurrencyOrOpen(token: String) = token.isNotEmpty() && token.all {
            Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() || it in "([{¿¡"
        }

        private val IS_PUNCT = Regex("^[,.?!:;\\\\%}\\])]+$")
        private val IS_OPEN_QUOTE = Regex("^['\"„“`]+$")

        fun detokenize(tokensIn: List<String>): String {
            val text = " " + tokensIn.joinToString(" ") + " "
            val tokens = text.replace(" @-@ ", "-").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val quoteCounts = HashMap<String, Int>()
            var prepend = " "
            val out = StringBuilder()
            for ((i, token) in tokens.withIndex()) {
                when {
                    isCurrencyOrOpen(token) -> {
                        out.append(prepend).append(token)
                        prepend = ""
                    }
                    IS_PUNCT.matches(token) -> {
                        out.append(token)
                        prepend = " "
                    }
                    i > 0 && token.length >= 2 && token[0] == '\'' && isAlpha(token.codePointAt(1)) -> {
                        out.append(token)
                        prepend = " "
                    }
                    IS_OPEN_QUOTE.matches(token) -> {
                        val q = if (token.all { it in "„“”" }) "\"" else token
                        val count = quoteCounts[q] ?: 0
                        if (count % 2 == 0) {
                            if (token == "'" && i > 0 && tokens[i - 1].endsWith("s")) {
                                out.append(token)
                                prepend = " "
                            } else {
                                out.append(prepend).append(token)
                                prepend = ""
                                quoteCounts[q] = count + 1
                            }
                        } else {
                            out.append(token)
                            prepend = " "
                            quoteCounts[q] = count + 1
                        }
                    }
                    else -> {
                        out.append(prepend).append(token)
                        prepend = " "
                    }
                }
            }
            return out.toString().replace(Regex(" {2,}"), " ").trim()
        }
    }
}
