package com.echobharat.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.echobharat.models.ModelRole
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * One AI4Bharat IndicTrans2 family (distilled 200M) on ONNX Runtime, greedy decoding.
 *
 * Graph contract, read off the exported models rather than taken from a README:
 *
 *   encoder      input_ids [B,S] int64, attention_mask [B,S] int64
 *                -> last_hidden_state [B,S,512]
 *   decoder      input_ids [B,T], encoder_attention_mask [B,S], encoder_hidden_states
 *                -> logits [B,T,V] + present.{0..17}.{decoder,encoder}.{key,value}
 *   decoder-past input_ids [B,1], encoder_attention_mask, past_key_values.* (as above)
 *                -> logits [B,1,V] + present.*.decoder.* (encoder KV does not change)
 *
 * The source is `[<src_tag>, <tgt_tag>, ...pieces, </s>]` over text that
 * [IndicTransText.preprocess] has normalised, tokenised and transliterated into Devanagari;
 * the decoder is primed with `decoder_start_token_id` (2).
 *
 * **Decoding.** With the past decoder present, the first step runs the full decoder once
 * — which also yields the cross-attention keys and values — and every later step feeds one
 * token plus the cache, so decoding is linear in output length. Without it every step
 * re-runs the whole prefix, O(n²), which is the fallback, not an error. Measured on a
 * desktop CPU: equal on 6-token sentences, 1.6–1.9× faster on 25–30 tokens. The two paths
 * can differ where two candidate tokens are within int8 rounding of each other (logit gaps
 * of ~0.01 were seen); both outputs are the model's translation.
 *
 * **Greedy, not beam.** Beam search would score somewhat better, but a subtly wrong beam
 * is worse than an honest greedy pass.
 */
class IndicTrans2Translator private constructor(
    override val family: String,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val decoderPast: OrtSession?,
    private val srcTokenizer: SpmBpeTokenizer,
    private val tgtTokenizer: SpmBpeTokenizer,
    private val tags: Map<String, Int>,
    private val maxSourceTokens: Int
) : TranslationEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    override val cached: Boolean get() = decoderPast != null

    /** decoder-past input -> the decoder output that feeds it ("past_key_values.3.encoder.key" <- "present.3.encoder.key"). */
    private val pastInputs: List<String> =
        decoderPast?.inputNames?.filter { it.startsWith("past_key_values.") }.orEmpty()

    override fun translate(text: String, source: String, target: String): String? {
        if (text.isBlank()) return null
        return try {
            translateInternal(text, source, target)
        } catch (e: Throwable) {
            Log.e(TAG, "translate $source->$target failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /** Exposed for the desktop test that pins both decoding paths to the reference. */
    internal fun translateInternal(text: String, source: String, target: String, useCache: Boolean = true): String? {
        val srcTag = tags[TranslationRoutes.FLORES[source]] ?: error("no tag for '$source' in $family")
        val tgtTag = tags[TranslationRoutes.FLORES[target]] ?: error("no tag for '$target' in $family")
        val started = System.currentTimeMillis()

        val prepared = IndicTransText.preprocess(text, source)
        val pieces = srcTokenizer.encode(prepared.text)
        val body = if (pieces.size > maxSourceTokens - 3) {
            Log.w(TAG, "source truncated from ${pieces.size} to ${maxSourceTokens - 3} tokens")
            pieces.copyOfRange(0, maxSourceTokens - 3)
        } else pieces

        val srcIds = LongArray(body.size + 3)
        srcIds[0] = srcTag.toLong()
        srcIds[1] = tgtTag.toLong()
        for (i in body.indices) srcIds[i + 2] = body[i].toLong()
        srcIds[srcIds.size - 1] = SpmBpeTokenizer.EOS.toLong()

        val shape = longArrayOf(1, srcIds.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(srcIds.size) { 1L }), shape).use { mask ->
                encoder.run(
                    mapOf("input_ids" to idTensor, "attention_mask" to mask),
                    setOf("last_hidden_state")
                ).use { enc ->
                    val hidden = enc.get(0) as OnnxTensor
                    val out = if (useCache && decoderPast != null) {
                        decodeCached(decoderPast, hidden, mask)
                    } else decodeCacheless(hidden, mask)
                    val result = IndicTransText.postprocess(tgtTokenizer.decode(out), target, prepared.placeholders)
                    Log.i(
                        TAG,
                        "$family $source->$target: ${srcIds.size} src -> ${out.size} out tokens in " +
                            "${System.currentTimeMillis() - started}ms (${if (useCache && cached) "cached" else "cacheless"})"
                    )
                    return result.ifBlank { null }
                }
            }
        }
    }

    private fun startToken(): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(DECODER_START.toLong())), longArrayOf(1, 1))

    /** Re-runs the whole prefix every step. Quadratic, but needs only the base decoder. */
    private fun decodeCacheless(hidden: OnnxTensor, mask: OnnxTensor): List<Int> {
        val generated = ArrayList<Int>(MAX_NEW_TOKENS)
        var prefix = longArrayOf(DECODER_START.toLong())
        for (step in 0 until MAX_NEW_TOKENS) {
            val next = OnnxTensor.createTensor(
                env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong())
            ).use { decIn ->
                decoder.run(
                    mapOf(
                        "input_ids" to decIn,
                        "encoder_attention_mask" to mask,
                        "encoder_hidden_states" to hidden
                    ),
                    // Only the logits: materialising the 72 cache tensors would be pure cost.
                    setOf("logits")
                ).use { out -> argmaxLastStep(out.get(0).value) }
            } ?: break
            if (next == SpmBpeTokenizer.EOS) break
            generated.add(next)
            prefix = prefix.copyOf(prefix.size + 1).also { it[it.size - 1] = next.toLong() }
        }
        return generated
    }

    /**
     * One full decoder pass, then one token at a time against the cache. The first pass's
     * result owns the encoder keys/values and stays open until decoding ends; each later
     * result owns the decoder cache the next step reads, and is closed once superseded.
     */
    private fun decodeCached(past: OrtSession, hidden: OnnxTensor, mask: OnnxTensor): List<Int> {
        val generated = ArrayList<Int>(MAX_NEW_TOKENS)
        startToken().use { start ->
            decoder.run(
                mapOf(
                    "input_ids" to start,
                    "encoder_attention_mask" to mask,
                    "encoder_hidden_states" to hidden
                )
            ).use { first ->
                val firstOut = first.associate { it.key to it.value }
                var next = argmaxLastStep(firstOut["logits"]?.value) ?: return generated
                var cache: Map<String, OnnxValue> = firstOut
                var previous: OrtSession.Result? = null
                try {
                    while (next != SpmBpeTokenizer.EOS && generated.size < MAX_NEW_TOKENS) {
                        generated.add(next)
                        val step = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(longArrayOf(next.toLong())), longArrayOf(1, 1)
                        )
                        val result = step.use {
                            val feed = HashMap<String, OnnxTensor>(pastInputs.size + 2)
                            feed["input_ids"] = it
                            feed["encoder_attention_mask"] = mask
                            for (name in pastInputs) {
                                val present = name.replaceFirst("past_key_values.", "present.")
                                // Decoder KV comes from the latest step; encoder KV from the first.
                                val source = if (".encoder." in name) firstOut else cache
                                feed[name] = source[present] as? OnnxTensor
                                    ?: error("decoder did not produce $present")
                            }
                            past.run(feed)
                        }
                        val out = result.associate { it.key to it.value }
                        previous?.close()
                        previous = result
                        cache = out
                        next = argmaxLastStep(out["logits"]?.value) ?: break
                    }
                } finally {
                    previous?.close()
                }
            }
        }
        return generated
    }

    /** logits arrive as [1][T][V]; only the last position matters for greedy decoding. */
    private fun argmaxLastStep(value: Any?): Int? {
        @Suppress("UNCHECKED_CAST")
        val batch = value as? Array<Array<FloatArray>> ?: run {
            Log.e(TAG, "unexpected logits layout: ${value?.javaClass}")
            return null
        }
        val last = batch.firstOrNull()?.lastOrNull() ?: return null
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in last.indices) {
            if (last[i] > bestVal) {
                bestVal = last[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { decoderPast?.close() }
    }

    companion object {
        private const val TAG = "IndicTrans2"
        private const val DECODER_START = 2
        private const val MAX_NEW_TOKENS = 128

        /** Files a family needs; [ModelRole.MT_DECODER_PAST] is optional. */
        val REQUIRED = listOf(
            ModelRole.MT_ENCODER, ModelRole.MT_DECODER, ModelRole.MT_BPE_SRC,
            ModelRole.MT_BPE_TGT, ModelRole.MT_META
        )

        /**
         * Opens a family from its resolved files, or returns why it cannot.
         *
         * [tags] come from the bundled manifest. The published meta.json only carries the
         * Hindi and English tags; where it does, they must agree with the manifest, which
         * catches a model swapped under an unchanged file name before it mistranslates.
         */
        fun open(
            family: String,
            files: Map<ModelRole, File>,
            tags: Map<String, Int>,
            threads: Int = 2
        ): Result<IndicTrans2Translator> = runCatching {
            val missing = REQUIRED.filter { files[it]?.isFile != true }
            if (missing.isNotEmpty()) throw java.io.FileNotFoundException(missing.joinToString())

            val meta = JSONObject(files.getValue(ModelRole.MT_META).readText())
            meta.optJSONObject("langTagIds")?.let { published ->
                for (key in published.keys()) {
                    val bundled = tags[key]
                    check(bundled == null || bundled == published.getInt(key)) {
                        "tag $key is ${published.getInt(key)} in meta.json but $bundled in the manifest"
                    }
                }
            }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val past = files[ModelRole.MT_DECODER_PAST]?.takeIf { it.isFile }

            IndicTrans2Translator(
                family = family,
                encoder = env.createSession(files.getValue(ModelRole.MT_ENCODER).absolutePath, opts),
                decoder = env.createSession(files.getValue(ModelRole.MT_DECODER).absolutePath, opts),
                decoderPast = past?.let { env.createSession(it.absolutePath, opts) },
                srcTokenizer = SpmBpeTokenizer.load(files.getValue(ModelRole.MT_BPE_SRC), meta.getInt("srcVocabSize")),
                tgtTokenizer = SpmBpeTokenizer.load(files.getValue(ModelRole.MT_BPE_TGT), meta.getInt("tgtVocabSize")),
                tags = tags,
                maxSourceTokens = meta.optInt("maxSourceTokens", 256)
            )
        }
    }
}
