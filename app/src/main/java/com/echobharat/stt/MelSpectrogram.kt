package com.echobharat.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Log-mel front-end matching NeMo's `AudioToMelSpectrogramPreprocessor` defaults, which
 * is what IndicConformer is trained against.
 *
 * Defaults mirror the Conformer recipe: 25 ms window, 10 ms hop, 512-point FFT, 80 mel
 * bins on the Slaney scale, 0.97 pre-emphasis, log with a zero guard, then per-feature
 * (per-bin) mean/variance normalisation.
 *
 * Only needed when the exported graph takes mel features. An export that accepts raw
 * audio bypasses this entirely — see [IndicConformerStt].
 */
class MelSpectrogram(
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
    private val nFft: Int = 512,
    private val hopLength: Int = 160,
    private val winLength: Int = 400,
    val nMels: Int = 80,
    private val preemph: Float = 0.97f
) {

    private companion object {
        /** NeMo's `log_zero_guard_value` for `log_zero_guard_type="add"`. */
        const val LOG_ZERO_GUARD = 5.9604645e-8f // 2^-24
    }

    private val window: FloatArray = FloatArray(winLength) { i ->
        // Periodic Hann, matching torch.hann_window(periodic=True).
        (0.5 - 0.5 * cos(2.0 * PI * i / winLength)).toFloat()
    }

    private val melFilters: Array<FloatArray> = buildMelFilterbank()

    /**
     * @param samples mono audio in [-1, 1]
     * @return `[nMels][frames]` normalised log-mel features
     */
    fun compute(samples: FloatArray): Array<FloatArray> {
        if (samples.isEmpty()) return Array(nMels) { FloatArray(0) }

        val emphasised = applyPreemphasis(samples)
        val frames = 1 + maxOf(0, (emphasised.size - winLength) / hopLength)
        if (frames <= 0) return Array(nMels) { FloatArray(0) }

        val out = Array(nMels) { FloatArray(frames) }
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        val bins = nFft / 2 + 1
        val power = FloatArray(bins)

        for (t in 0 until frames) {
            val offset = t * hopLength
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (i in 0 until winLength) {
                val idx = offset + i
                if (idx < emphasised.size) re[i] = emphasised[idx] * window[i]
            }

            fft(re, im)

            for (k in 0 until bins) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }

            for (m in 0 until nMels) {
                var acc = 0f
                val filter = melFilters[m]
                for (k in 0 until bins) {
                    val w = filter[k]
                    if (w != 0f) acc += w * power[k]
                }
                out[m][t] = ln(acc + LOG_ZERO_GUARD)
            }
        }

        normalisePerFeature(out)
        return out
    }

    private fun applyPreemphasis(x: FloatArray): FloatArray {
        if (preemph == 0f) return x
        val y = FloatArray(x.size)
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = x[i] - preemph * x[i - 1]
        return y
    }

    /** NeMo `normalize="per_feature"`: zero mean, unit variance per mel bin. */
    private fun normalisePerFeature(mel: Array<FloatArray>) {
        val frames = mel[0].size
        if (frames == 0) return
        for (m in mel.indices) {
            val row = mel[m]
            // Accumulate in double: log-mel values cluster tightly around a large
            // negative number, so float summation loses the difference we need here.
            var sum = 0.0
            for (v in row) sum += v
            val mean = sum / frames

            var sumSquares = 0.0
            for (v in row) {
                val d = v - mean
                sumSquares += d * d
            }
            // NeMo uses the unbiased estimator and a small epsilon for stability.
            val variance = if (frames > 1) sumSquares / (frames - 1) else 0.0
            val std = sqrt(variance) + 1e-5

            for (i in row.indices) row[i] = ((row[i] - mean) / std).toFloat()
        }
    }

    /** Librosa-equivalent mel filterbank: Slaney scale, Slaney (area) normalisation. */
    internal fun buildMelFilterbank(): Array<FloatArray> {
        val bins = nFft / 2 + 1
        val fMin = 0f
        val fMax = sampleRate / 2f

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + (melMax - melMin) * i / (nMels + 1)
        }
        val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }

        val fftFreqs = FloatArray(bins) { it * sampleRate.toFloat() / nFft }

        val filters = Array(nMels) { FloatArray(bins) }
        for (m in 0 until nMels) {
            val lower = hzPoints[m]
            val centre = hzPoints[m + 1]
            val upper = hzPoints[m + 2]

            for (k in 0 until bins) {
                val f = fftFreqs[k]
                val rise = if (centre > lower) (f - lower) / (centre - lower) else 0f
                val fall = if (upper > centre) (upper - f) / (upper - centre) else 0f
                filters[m][k] = maxOf(0f, minOf(rise, fall))
            }

            // Slaney normalisation: each filter integrates to a constant area.
            val enorm = 2f / (hzPoints[m + 2] - hzPoints[m])
            for (k in 0 until bins) filters[m][k] *= enorm
        }
        return filters
    }

    // Slaney mel scale: linear below 1 kHz, logarithmic above.
    private fun hzToMel(hz: Float): Float {
        val fSp = 200f / 3f
        val minLogHz = 1000f
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4f) / 27f
        return if (hz < minLogHz) hz / fSp else minLogMel + ln(hz / minLogHz) / logStep
    }

    private fun melToHz(mel: Float): Float {
        val fSp = 200f / 3f
        val minLogHz = 1000f
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4f) / 27f
        return if (mel < minLogMel) fSp * mel else minLogHz * exp(logStep * (mel - minLogMel))
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT. [nFft] is a power of two. */
    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
