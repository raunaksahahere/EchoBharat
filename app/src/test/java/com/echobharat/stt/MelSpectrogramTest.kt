package com.echobharat.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mel front-end has no observable output short of a transcription, so a wrong FFT
 * or filterbank would show up only as silently garbled STT on a phone. These pin the
 * numerics down on the JVM instead.
 */
class MelSpectrogramTest {

    private val mel = MelSpectrogram()

    /** Reference O(n²) DFT to check the radix-2 implementation against. */
    private fun naiveDft(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (k in 0 until n) {
            var sumR = 0.0
            var sumI = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                sumR += input[t] * cos(angle)
                sumI += input[t] * sin(angle)
            }
            re[k] = sumR.toFloat()
            im[k] = sumI.toFloat()
        }
        return re to im
    }

    @Test
    fun `fft matches a naive DFT on random input`() {
        val n = 256
        val random = java.util.Random(20260831)
        val signal = FloatArray(n) { random.nextGaussian().toFloat() }

        val (expectedRe, expectedIm) = naiveDft(signal)

        val re = signal.copyOf()
        val im = FloatArray(n)
        mel.fft(re, im)

        for (k in 0 until n) {
            assertEquals("real[$k]", expectedRe[k], re[k], 1e-2f)
            assertEquals("imag[$k]", expectedIm[k], im[k], 1e-2f)
        }
    }

    @Test
    fun `fft puts a pure tone in the expected bin`() {
        val n = 512
        val bin = 64
        val re = FloatArray(n) { cos(2.0 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)

        mel.fft(re, im)

        val magnitudes = FloatArray(n / 2 + 1) { k -> abs(re[k]) + abs(im[k]) }
        val peak = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertEquals("peak bin", bin, peak)
    }

    @Test
    fun `mel filterbank is well formed`() {
        val filters = mel.buildMelFilterbank()

        assertEquals("filter count", 80, filters.size)
        assertEquals("bins per filter", 512 / 2 + 1, filters[0].size)

        // Every filter must carry energy, be non-negative, and peak in a rising order.
        var previousPeak = -1
        for ((index, filter) in filters.withIndex()) {
            assertTrue("filter $index is all zero", filter.any { it > 0f })
            assertTrue("filter $index has a negative weight", filter.all { it >= 0f })

            val peak = filter.indices.maxByOrNull { filter[it] }!!
            assertTrue("filter $index peaks below filter ${index - 1}", peak >= previousPeak)
            previousPeak = peak
        }
    }

    @Test
    fun `compute returns per-feature normalised frames`() {
        // Broadband noise, so every mel bin carries energy. A pure tone would leave the
        // upper bins at the log-guard floor, where the epsilon deliberately suppresses
        // normalisation and unit variance is not expected.
        val random = java.util.Random(20260831)
        val samples = FloatArray(16_000) { random.nextGaussian().toFloat() * 0.3f }

        val features = mel.compute(samples)

        assertEquals("mel bins", 80, features.size)
        val frames = features[0].size
        assertTrue("expected many frames, got $frames", frames > 90)

        // per_feature normalisation => every bin is zero-mean.
        var unitVarianceBins = 0
        for ((index, row) in features.withIndex()) {
            val mean = row.sum() / frames
            assertEquals("bin $index mean", 0f, mean, 1e-3f)

            val variance = row.sumOf { ((it - mean) * (it - mean)).toDouble() } / (frames - 1)

            // The 1e-5 epsilon on the divisor only ever shrinks variance, and does so
            // noticeably in near-silent bins — that guard is deliberate (it stops an
            // empty bin being amplified into noise), so unit variance is an upper bound.
            assertTrue("bin $index variance $variance exceeds 1", variance <= 1.0 + 1e-6)
            if (variance > 0.99) unitVarianceBins++
        }

        // With energy in every bin, normalisation should reach unit variance throughout.
        assertTrue(
            "expected nearly all bins at unit variance, got $unitVarianceBins of ${features.size}",
            unitVarianceBins >= features.size - 2
        )
    }

    @Test
    fun `compute tolerates audio shorter than one window`() {
        val features = mel.compute(FloatArray(100) { 0.1f })
        assertEquals(80, features.size)
    }

    @Test
    fun `compute tolerates empty audio`() {
        val features = mel.compute(FloatArray(0))
        assertEquals(80, features.size)
        assertEquals(0, features[0].size)
    }
}
