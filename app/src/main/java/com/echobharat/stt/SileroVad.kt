package com.echobharat.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (MIT), bundled in `assets/models/vad/`.
 *
 * Gates the expensive STT model so idle listening stays cheap (PRD efficiency metric).
 *
 * The v5 graph is stateful: it takes the previous LSTM state plus a 64-sample context
 * window carried over from the previous frame, and returns the updated state alongside
 * the speech probability.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) : Closeable {

    companion object {
        private const val TAG = "SileroVad"
        private const val ASSET = "models/vad/silero_vad_16k.onnx"

        private const val CONTEXT_SAMPLES = 64
        private const val STATE_DIM = 128

        /**
         * Loads the bundled VAD. The asset is copied to cache on first use because
         * ONNX Runtime needs a real file path or a byte array, not an AssetManager stream.
         */
        fun load(context: Context): SileroVad? = try {
            val cached = File(context.cacheDir, "silero_vad_16k.onnx")
            if (!cached.isFile || cached.length() == 0L) {
                context.assets.open(ASSET).use { input ->
                    cached.outputStream().use { input.copyTo(it) }
                }
                Log.i(TAG, "Extracted VAD model to ${cached.absolutePath} (${cached.length()} bytes)")
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            SileroVad(env, opts.use { env.createSession(cached.absolutePath, it) }).also {
                Log.i(TAG, "VAD loaded")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VAD_LOAD_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // [2, 1, 128] LSTM state, carried across frames.
    private var state = FloatArray(2 * STATE_DIM)
    private var context = FloatArray(CONTEXT_SAMPLES)

    /** Clears state between utterances so one utterance cannot bias the next. */
    fun reset() {
        state = FloatArray(2 * STATE_DIM)
        context = FloatArray(CONTEXT_SAMPLES)
    }

    /**
     * @param frame exactly [AudioCapture.FRAME_SAMPLES] samples in [-1, 1].
     * @return probability the frame contains speech, or null if inference failed.
     */
    fun speechProbability(frame: FloatArray): Float? {
        require(frame.size == AudioCapture.FRAME_SAMPLES) {
            "VAD expects ${AudioCapture.FRAME_SAMPLES} samples, got ${frame.size}"
        }

        // The graph consumes [context .. frame]; the tail becomes the next context.
        val input = FloatArray(CONTEXT_SAMPLES + frame.size)
        context.copyInto(input, 0)
        frame.copyInto(input, CONTEXT_SAMPLES)

        return try {
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())
            )
            val stateTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_DIM.toLong())
            )
            val srTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(AudioCapture.SAMPLE_RATE.toLong())), longArrayOf()
            )

            inputTensor.use { i ->
                stateTensor.use { s ->
                    srTensor.use { sr ->
                        session.run(mapOf("input" to i, "state" to s, "sr" to sr)).use { out ->
                            @Suppress("UNCHECKED_CAST")
                            val prob = (out.get(0).value as Array<FloatArray>)[0][0]

                            // stateN comes back as [2][1][128]; flatten for the next call.
                            @Suppress("UNCHECKED_CAST")
                            val next = out.get(1).value as Array<Array<FloatArray>>
                            val flat = FloatArray(2 * STATE_DIM)
                            next[0][0].copyInto(flat, 0)
                            next[1][0].copyInto(flat, STATE_DIM)
                            state = flat

                            input.copyInto(context, 0, input.size - CONTEXT_SAMPLES, input.size)
                            prob
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VAD_INFERENCE_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}
