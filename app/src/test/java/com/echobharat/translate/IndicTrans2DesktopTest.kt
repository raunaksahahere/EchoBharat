package com.echobharat.translate

import com.echobharat.models.ModelRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Runs the real int8 IndicTrans2 models through the Kotlin translator on the desktop JVM
 * and checks the translations against AI4Bharat's reference pipeline.
 *
 * Opt-in, because it needs ~800 MB of models and the desktop ONNX Runtime:
 *
 *   ./gradlew testDebugUnitTest --tests '*IndicTrans2DesktopTest*' -PmtModels=<dir>
 *
 * where `<dir>` holds `indic-en/` and `en-indic/`, each with the upstream
 * `encoder_model.onnx`, `decoder_model.onnx`, `decoder_with_past_model.onnx` and the
 * `bpe-src.tsv`, `bpe-tgt.tsv`, `mt-meta.json` that `model-export/export_mt_vocab.py`
 * writes. Skipped everywhere else.
 */
class IndicTrans2DesktopTest {

    companion object {
        private val dir: File? = System.getProperty("echobharat.mtModels")?.let(::File)
        private val engines = HashMap<String, IndicTrans2Translator>()

        @BeforeClass
        @JvmStatic
        fun requireModels() {
            assumeTrue("set -PmtModels=<dir> to run", dir?.isDirectory == true)
        }

        /** Tags from the manifest the app ships, not from the test's own copy. */
        private fun tags(family: String): Map<String, Int> {
            val manifest = JSONObject(File("src/main/assets/models/manifest.json").readText())
            val t = manifest.getJSONObject("translation").getJSONObject("tags").getJSONObject(family)
            return t.keys().asSequence().associateWith { t.getInt(it) }
        }

        fun engine(family: String): IndicTrans2Translator = engines.getOrPut(family) {
            val d = File(dir, family)
            IndicTrans2Translator.open(
                family,
                mapOf(
                    ModelRole.MT_ENCODER to File(d, "encoder_model.onnx"),
                    ModelRole.MT_DECODER to File(d, "decoder_model.onnx"),
                    ModelRole.MT_DECODER_PAST to File(d, "decoder_with_past_model.onnx"),
                    ModelRole.MT_BPE_SRC to File(d, "bpe-src.tsv"),
                    ModelRole.MT_BPE_TGT to File(d, "bpe-tgt.tsv"),
                    ModelRole.MT_META to File(d, "mt-meta.json")
                ),
                tags(family),
                threads = 4
            ).getOrThrow()
        }
    }

    private data class Case(
        val src: String, val tgt: String, val input: String,
        val expected: String, val expectedCached: String
    )

    private val cases: List<Case> by lazy {
        javaClass.classLoader!!.getResourceAsStream("mt-golden.tsv")!!.bufferedReader().readLines()
            .filter { it.startsWith("post\t") }
            .map { it.split('\t').let { f -> Case(f[1], f[2], f[3], f[5], f[6]) } }
    }

    private fun familyOf(c: Case) = TranslationRoutes.route(c.src, c.tgt)!!.single().family

    @Test
    fun `cacheless decoding reproduces the reference translation exactly`() {
        val failures = cases.mapNotNull { c ->
            val got = engine(familyOf(c)).translateInternal(c.input, c.src, c.tgt, useCache = false)
            if (got == c.expected) null else "[${c.src}->${c.tgt}] ${c.input}\n   want: ${c.expected}\n   got : $got"
        }
        assertEquals(
            "${failures.size}/${cases.size} differ:\n" + failures.take(10).joinToString("\n"),
            0, failures.size
        )
    }

    /**
     * The KV-cached path is pinned to the reference's own KV-cached decoding, not to the
     * cacheless one: the two graphs are quantised separately, so where the top two tokens
     * are within int8 rounding (gaps of ~0.01 were measured) they pick different, equally
     * valid words — about one sentence in eight on this corpus.
     */
    @Test
    fun `cached decoding reproduces the reference cached translation exactly`() {
        val failures = cases.mapNotNull { c ->
            val got = engine(familyOf(c)).translateInternal(c.input, c.src, c.tgt, useCache = true)
            if (got == c.expectedCached) null
            else "[${c.src}->${c.tgt}] ${c.input}\n   want: ${c.expectedCached}\n   got : $got"
        }
        val differ = cases.count { it.expected != it.expectedCached }
        println("cached and cacheless reference outputs differ on $differ/${cases.size} sentences")
        assertEquals(
            "${failures.size}/${cases.size} differ:\n" + failures.take(10).joinToString("\n"),
            0, failures.size
        )
    }

    @Test
    fun `indian languages translate into each other through english`() {
        val ta = "எனக்கு தண்ணீர் மற்றும் மருந்து தேவை."
        val en = engine(TranslationRoutes.INDIC_EN).translate(ta, "ta", "en")!!
        val hi = engine(TranslationRoutes.EN_INDIC).translate(en, "en", "hi")!!
        println("ta -> en -> hi: $ta -> $en -> $hi")
        assertEquals("I need water and medicine.", en)
        assertTrue(hi, hi.contains("पानी") && hi.contains("दवा"))
    }
}
