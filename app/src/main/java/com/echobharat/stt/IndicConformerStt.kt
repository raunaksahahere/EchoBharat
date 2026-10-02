package com.echobharat.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * AI4Bharat IndicConformer (MIT) via ONNX Runtime Mobile.
 *
 * NeMo exports vary — some graphs take raw audio, most take log-mel features, and the
 * input names differ between export scripts. Rather than hardcode one shape, the session
 * is introspected at load time and the feed is built to match, so a re-export does not
 * require a code change.
 */
class IndicConformerStt private constructor(
    override val lang: String,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokens: List<String>,
    /** Index of the CTC blank. Not always `tokens.size` — see [findBlank]. */
    private val blankId: Int,
    private val layout: Layout
) : SttEngine {

    /** How this particular export wants to be fed. */
    private data class Layout(
        val audioInput: String,
        val lengthInput: String?,
        /** True when the graph takes raw waveform rather than mel features. */
        val takesRawAudio: Boolean,
        /** For mel input: true when the graph wants [B, mels, T], false for [B, T, mels]. */
        val melsFirst: Boolean,
        val nMels: Int
    )

    companion object {
        private const val TAG = "IndicConformerStt"

        private val AUDIO_INPUT_NAMES = listOf("audio_signal", "audio", "input", "waveform", "x", "processed_signal")
        private val LENGTH_INPUT_NAMES = listOf("length", "audio_signal_length", "lengths", "input_length", "processed_signal_length")

        /**
         * @param modelFile exported IndicConformer ONNX
         * @param tokensFile one token per line, in vocabulary order; the CTC blank is
         *   appended automatically if the file does not already end with one.
         */
        fun load(lang: String, modelFile: File, tokensFile: File): IndicConformerStt? = try {
            val vocab = parseTokens(tokensFile)
            if (vocab.isEmpty()) throw IllegalStateException("Empty token file: ${tokensFile.name}")
            val blankId = findBlank(vocab)

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // NNAPI is opportunistic: many mid-range chipsets fall back to CPU for
                // Conformer ops, and a hard failure here would take STT down entirely.
                runCatching { addNnapi() }
                    .onFailure { Log.i(TAG, "NNAPI unavailable, using CPU: ${it.message}") }
            }

            val session = opts.use { env.createSession(modelFile.absolutePath, it) }
            val layout = try {
                describe(session)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
            Log.i(
                TAG,
                "Loaded '$lang' from ${modelFile.name} (${modelFile.length()} bytes), " +
                    "vocab=${vocab.size}, blank=$blankId, layout=$layout"
            )
            IndicConformerStt(lang, env, session, vocab, blankId, layout)
        } catch (e: Throwable) {
            Log.e(TAG, "STT_LOAD_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }

        /**
         * Reads a token list in either published form:
         *  - `"<symbol> <id>"` per line, as sherpa/NeMo exports ship it, or
         *  - a bare symbol per line, positionally indexed.
         *
         * Mis-detecting this silently shifts every token, so the id form wins whenever
         * any line carries a trailing integer.
         */
        private fun parseTokens(file: File): List<String> {
            val lines = file.readLines().map { it.trimEnd('\n', '\r') }.filter { it.isNotBlank() }
            val byId = HashMap<Int, String>(lines.size * 2)

            for (line in lines) {
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) continue
                val id = line.substring(cut + 1).toIntOrNull() ?: continue
                byId[id] = line.substring(0, cut)
            }

            if (byId.isEmpty()) return lines

            val size = (byId.keys.maxOrNull() ?: -1) + 1
            return List(size) { byId[it] ?: "" }
        }

        /**
         * Locates the CTC blank. NeMo BPE vocabularies carry it as the last entry
         * (`<blk>`) *inside* the vocabulary, while some exports append it past the end;
         * assuming the latter makes the decoder emit a literal "<blk>" in the text.
         */
        private fun findBlank(tokens: List<String>): Int {
            val named = tokens.indexOfFirst {
                it == "<blk>" || it == "<blank>" || it == "<pad>" || it == "<eps>"
            }
            return if (named >= 0) named else tokens.size
        }

        /** Works out how to feed this export from its declared input names and shapes. */
        private fun describe(session: OrtSession): Layout {
            val inputs = session.inputInfo
            val names = inputs.keys

            val audioName = AUDIO_INPUT_NAMES.firstOrNull { it in names }
                ?: names.firstOrNull { it !in LENGTH_INPUT_NAMES }
                ?: throw IllegalStateException("No audio input found; inputs=$names")

            val lengthName = LENGTH_INPUT_NAMES.firstOrNull { it in names }

            val shape = (inputs[audioName]?.info as? ai.onnxruntime.TensorInfo)?.shape
                ?: throw IllegalStateException("Input '$audioName' is not a tensor")

            // [B, T] is raw audio; [B, mels, T] or [B, T, mels] is a mel feature map.
            if (shape.size <= 2) {
                return Layout(audioName, lengthName, takesRawAudio = true, melsFirst = false, nMels = 0)
            }

            // A static mel count pins the orientation; otherwise NeMo's [B, mels, T] holds.
            val dim1 = shape[1]
            val dim2 = shape[2]
            val melsFirst: Boolean
            val nMels: Int
            when {
                dim1 > 0 && (dim2 <= 0 || dim1 in 32..256) -> { melsFirst = true; nMels = dim1.toInt() }
                dim2 > 0 && dim2 in 32..256 -> { melsFirst = false; nMels = dim2.toInt() }
                else -> { melsFirst = true; nMels = 80 }
            }
            return Layout(audioName, lengthName, takesRawAudio = false, melsFirst = melsFirst, nMels = nMels)
        }
    }

    private val mel: MelSpectrogram? =
        if (layout.takesRawAudio) null else MelSpectrogram(nMels = layout.nMels)

    override fun transcribe(samples: FloatArray): String? {
        if (samples.isEmpty()) {
            Log.w(TAG, "transcribe called with empty audio")
            return null
        }

        return try {
            val started = System.currentTimeMillis()
            val feeds = HashMap<String, OnnxTensor>()

            val frameCount: Long
            if (layout.takesRawAudio) {
                feeds[layout.audioInput] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())
                )
                frameCount = samples.size.toLong()
            } else {
                val features = mel!!.compute(samples)
                val mels = features.size
                val frames = features[0].size
                if (frames == 0) {
                    Log.w(TAG, "Audio too short for a single mel frame (${samples.size} samples)")
                    return null
                }

                val flat = FloatArray(mels * frames)
                if (layout.melsFirst) {
                    for (m in 0 until mels) features[m].copyInto(flat, m * frames)
                } else {
                    for (t in 0 until frames) {
                        for (m in 0 until mels) flat[t * mels + m] = features[m][t]
                    }
                }
                val shape = if (layout.melsFirst) {
                    longArrayOf(1, mels.toLong(), frames.toLong())
                } else {
                    longArrayOf(1, frames.toLong(), mels.toLong())
                }
                feeds[layout.audioInput] = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
                frameCount = frames.toLong()
            }

            layout.lengthInput?.let { name ->
                feeds[name] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(frameCount)), longArrayOf(1)
                )
            }

            val text = try {
                session.run(feeds).use { results ->
                    val logits = extractLogits(results.get(0).value)
                    if (logits == null) {
                        Log.e(TAG, "Unexpected output type: ${results.get(0).value?.javaClass}")
                        null
                    } else {
                        greedyCtcDecode(logits)
                    }
                }
            } finally {
                feeds.values.forEach { runCatching { it.close() } }
            }

            val elapsed = System.currentTimeMillis() - started
            val seconds = samples.size.toFloat() / AudioCapture.SAMPLE_RATE
            Log.i(
                TAG,
                "STT[$lang] ${"%.2f".format(seconds)}s audio in ${elapsed}ms " +
                    "(RTF ${"%.2f".format(elapsed / 1000f / seconds)}), chars=${text?.length ?: 0}"
            )
            text
        } catch (e: Throwable) {
            Log.e(TAG, "STT_INFERENCE_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /** Normalises the several shapes NeMo exports emit into `[time][vocab]`. */
    @Suppress("UNCHECKED_CAST")
    private fun extractLogits(value: Any?): Array<FloatArray>? = when (value) {
        is Array<*> -> when (val first = value.firstOrNull()) {
            // [B][T][V] — take the single batch item.
            is Array<*> -> if (first.firstOrNull() is FloatArray) {
                (value[0] as Array<FloatArray>)
            } else null
            // [T][V] already.
            is FloatArray -> value as Array<FloatArray>
            else -> null
        }
        else -> null
    }

    /**
     * Greedy CTC: take the argmax per frame, drop blanks and repeats, then join.
     *
     * NeMo BPE vocabularies mark word starts with U+2581; a character vocabulary has no
     * such marker, so both are handled.
     */
    private fun greedyCtcDecode(logits: Array<FloatArray>): String {
        val blank = blankId
        val ids = ArrayList<Int>(logits.size)
        var previous = -1

        for (frame in logits) {
            var best = 0
            var bestScore = frame[0]
            for (i in 1 until frame.size) {
                if (frame[i] > bestScore) {
                    bestScore = frame[i]
                    best = i
                }
            }
            if (best != previous && best != blank && best < tokens.size) ids.add(best)
            previous = best
        }

        val sb = StringBuilder()
        for (id in ids) {
            val token = tokens[id]
            when {
                token.startsWith('▁') -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(token.substring(1))
                }
                token.isEmpty() || token == "<unk>" || token == "<pad>" ||
                    token == "<s>" || token == "</s>" || token == "<blk>" -> Unit
                else -> sb.append(token)
            }
        }
        return sb.toString().trim()
    }

    override fun close() {
        runCatching { session.close() }
        Log.i(TAG, "Unloaded STT '$lang'")
    }
}
