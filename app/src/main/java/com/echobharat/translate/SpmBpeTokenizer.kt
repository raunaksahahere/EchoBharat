package com.echobharat.translate

import java.io.File
import java.text.Normalizer

/**
 * SentencePiece **BPE** encoding/decoding, reimplemented so no native SentencePiece
 * binary has to ship on the phone.
 *
 * IndicTrans2's tokenisers are BPE (confirmed by reading `trainer_spec.model_type` out of
 * model.SRC/model.TGT), which is the easy case: encoding is "repeatedly merge the adjacent
 * pair with the best score". The unigram variant would have needed a Viterbi lattice.
 *
 * Input is `bpe-src.tsv` / `bpe-tgt.tsv` produced by `model-export/export_mt_vocab.py`:
 * `piece <TAB> score <TAB> graph_id`, in SentencePiece's own piece order. The score drives
 * merge order; the graph id is what the ONNX vocabulary actually uses and deliberately does
 * *not* match SentencePiece's internal index — conflating the two yields fluent-looking
 * nonsense, so they are kept distinct here.
 *
 * This implementation was checked piece-for-piece against the real `sentencepiece` library
 * on Hindi and English sentences before it was written; see the same algorithm in
 * `model-export` history.
 */
class SpmBpeTokenizer private constructor(
    private val score: HashMap<String, Float>,
    private val graphId: HashMap<String, Int>,
    private val pieceForId: Array<String?>
) {

    /**
     * Encodes to graph ids. Pieces the graph vocabulary does not contain resolve to [UNK]
     * rather than being dropped, so a bad pack degrades loudly instead of silently
     * shortening the sentence.
     */
    fun encode(text: String): IntArray {
        val pieces = encodeToPieces(text)
        return IntArray(pieces.size) { i ->
            val id = graphId[pieces[i]] ?: UNK
            if (id < 0) UNK else id
        }
    }

    /** Exposed for tests: the piece sequence before ids are looked up. */
    fun encodeToPieces(text: String): List<String> {
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        if (normalised.isEmpty()) return emptyList()

        // add_dummy_prefix=true in the model's normalizer_spec: a leading space becomes the
        // word-start marker, which is why "help" and " help" tokenise differently.
        val marked = (" $normalised").replace(' ', WORD_START)

        // Code points, not chars — Devanagari stays inside the BMP but surrogate pairs in
        // user text (emoji) must not be split into unpaired halves.
        val symbols = ArrayList<String>(marked.length)
        var i = 0
        while (i < marked.length) {
            val cp = marked.codePointAt(i)
            val n = Character.charCount(cp)
            symbols.add(marked.substring(i, i + n))
            i += n
        }

        // Greedy best-score merging. The scan is over the symbol list (tens of entries for
        // a spoken utterance), not over the 128k vocabulary, so this stays cheap.
        while (symbols.size > 1) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestAt = -1
            for (j in 0 until symbols.size - 1) {
                val s = score[symbols[j] + symbols[j + 1]] ?: continue
                if (s > bestScore) {
                    bestScore = s
                    bestAt = j
                }
            }
            if (bestAt < 0) break
            symbols[bestAt] = symbols[bestAt] + symbols[bestAt + 1]
            symbols.removeAt(bestAt + 1)
        }
        return symbols
    }

    /**
     * Turns generated ids back into text. Special ids are skipped; the word-start marker
     * becomes a space, matching `convert_tokens_to_string` in the reference tokeniser.
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == BOS || id == PAD || id == EOS) continue
            val piece = pieceForId.getOrNull(id)
            if (piece == null || piece == UNK_PIECE) continue
            sb.append(piece)
        }
        return sb.toString().replace(WORD_START, ' ').trim()
    }

    companion object {
        const val BOS = 0
        const val PAD = 1
        const val EOS = 2
        const val UNK = 3

        private const val UNK_PIECE = "<unk>"

        /** SentencePiece's word-boundary marker, U+2581 LOWER ONE EIGHTH BLOCK. */
        private const val WORD_START = '▁'

        /**
         * @param maxGraphId size of the ONNX vocabulary for this side, so the reverse
         *   lookup can be a flat array instead of a second hash map.
         */
        fun load(tsv: File, maxGraphId: Int): SpmBpeTokenizer {
            val score = HashMap<String, Float>(1 shl 18)
            val graphId = HashMap<String, Int>(1 shl 18)
            val pieceForId = arrayOfNulls<String>(maxGraphId)

            tsv.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    // Split from the right: a piece can itself contain a tab.
                    val idCut = line.lastIndexOf('\t')
                    if (idCut <= 0) continue
                    val scoreCut = line.lastIndexOf('\t', idCut - 1)
                    if (scoreCut <= 0) continue

                    val piece = line.substring(0, scoreCut)
                    val s = line.substring(scoreCut + 1, idCut).toFloatOrNull() ?: continue
                    val id = line.substring(idCut + 1).toIntOrNull() ?: continue

                    // First occurrence wins, mirroring SentencePiece's own piece ordering.
                    if (score.putIfAbsent(piece, s) != null) continue
                    graphId[piece] = id
                    if (id in 0 until maxGraphId && pieceForId[id] == null) {
                        pieceForId[id] = piece
                    }
                }
            }
            return SpmBpeTokenizer(score, graphId, pieceForId)
        }
    }
}
