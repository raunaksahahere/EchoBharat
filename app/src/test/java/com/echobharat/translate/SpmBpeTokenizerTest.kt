package com.echobharat.translate

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tokeniser is the piece most able to fail silently: a wrong merge order still
 * produces a plausible token sequence, the model still runs, and what comes out the far
 * end is fluent nonsense rather than an error. These tests pin the behaviour that the
 * real vocabulary relies on.
 *
 * The full algorithm was separately checked piece-for-piece against the reference
 * `sentencepiece` library on Hindi and English sentences during export; what is fixed
 * here is the merge rule, the id mapping and the round trip.
 */
class SpmBpeTokenizerTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * piece, score, graph id. Scores are chosen so the intended merge order is the only
     * one a correct implementation can produce.
     */
    private fun tokenizer(vararg rows: Triple<String, Double, Int>): SpmBpeTokenizer {
        val f = File(temp.root, "bpe.tsv")
        f.writeText(rows.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" } + "\n")
        val maxId = rows.maxOf { it.third } + 1
        return SpmBpeTokenizer.load(f, maxId)
    }

    private fun base() = arrayOf(
        Triple("<unk>", 0.0, 3),
        Triple("▁", -1.0, 10),
        Triple("h", -1.0, 11),
        Triple("e", -1.0, 12),
        Triple("l", -1.0, 13),
        Triple("p", -1.0, 14),
        Triple("▁h", -5.0, 20),
        Triple("el", -2.0, 21),
        Triple("▁hel", -3.0, 22),
        Triple("▁help", -4.0, 23)
    )

    @Test
    fun `merges by best score until nothing merges`() {
        val t = tokenizer(*base())
        // "el" (-2) beats "▁h" (-5) first, then "▁hel" (-3), then "▁help" (-4).
        assertEquals(listOf("▁help"), t.encodeToPieces("help"))
    }

    @Test
    fun `leading space marker is added so word starts are distinguishable`() {
        val t = tokenizer(*base())
        // add_dummy_prefix: the piece carries the word-start marker, not a bare "help".
        assertEquals(listOf("▁help"), t.encodeToPieces("help"))
        assertEquals(23, t.encode("help").single())
    }

    @Test
    fun `pieces absent from the graph vocabulary become unk rather than vanishing`() {
        // "z" is a known SentencePiece piece but carries id -1: present in the model,
        // absent from the ONNX vocabulary.
        val t = tokenizer(
            Triple("<unk>", 0.0, 3),
            Triple("▁", -1.0, 10),
            Triple("z", -1.0, -1)
        )
        val ids = t.encode("z")
        assertEquals(2, ids.size)            // the marker and the letter, nothing dropped
        assertEquals(SpmBpeTokenizer.UNK, ids[1])
    }

    @Test
    fun `decode turns the word marker back into spaces and drops specials`() {
        val t = tokenizer(
            Triple("<unk>", 0.0, 3),
            Triple("▁need", -1.0, 30),
            Triple("▁help", -2.0, 31)
        )
        val text = t.decode(
            listOf(SpmBpeTokenizer.EOS, 30, 31, SpmBpeTokenizer.PAD, SpmBpeTokenizer.BOS)
        )
        assertEquals("need help", text)
    }

    @Test
    fun `empty and blank input produce no tokens`() {
        val t = tokenizer(*base())
        assertEquals(emptyList<String>(), t.encodeToPieces(""))
        assertEquals(emptyList<String>(), t.encodeToPieces("   "))
    }

    @Test
    fun `unmergeable characters survive as separate pieces`() {
        val t = tokenizer(
            Triple("<unk>", 0.0, 3),
            Triple("▁", -1.0, 10),
            Triple("a", -1.0, 40),
            Triple("b", -1.0, 41)
        )
        assertEquals(listOf("▁", "a", "b"), t.encodeToPieces("ab"))
    }
}
