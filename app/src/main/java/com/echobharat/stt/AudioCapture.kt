package com.echobharat.stt

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.thread

/**
 * Microphone capture at the rate the speech models expect: 16 kHz, mono, PCM-16.
 *
 * Emits fixed [FRAME_SAMPLES]-sample frames as normalised floats in [-1, 1], which is
 * both what Silero VAD requires and what the STT front-end consumes.
 */
class AudioCapture {

    companion object {
        private const val TAG = "AudioCapture"

        const val SAMPLE_RATE = 16_000

        /** Silero VAD operates on exactly 512 samples per frame at 16 kHz (32 ms). */
        const val FRAME_SAMPLES = 512
    }

    /**
     * Cold flow of audio frames. Recording starts on collection and stops on cancellation.
     *
     * @throws IllegalStateException if the recorder cannot be initialised.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<FloatArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer")
        }

        // Buffer generously so a scheduling hiccup does not drop audio mid-utterance.
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * 2 * 8)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialise (state=${recorder.state})")
        }

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord failed to start (state=${recorder.recordingState})"
            }
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
        Log.i(TAG, "Capture started: ${SAMPLE_RATE}Hz mono PCM16, buffer=$bufferBytes bytes")

        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val worker = thread(name = "echobharat-audio-capture", isDaemon = true) {
            val pcm = ShortArray(FRAME_SAMPLES)
            try {
                while (running.get()) {
                    var filled = 0
                    // read() can return a short count; top the frame up before emitting.
                    while (filled < FRAME_SAMPLES && running.get()) {
                        val n = recorder.read(pcm, filled, FRAME_SAMPLES - filled)
                        if (n <= 0) {
                            if (!running.get()) return@thread
                            // A dead/revoked microphone must end capture, not spin forever.
                            throw IllegalStateException("AudioRecord.read failed: $n")
                        }
                        filled += n
                    }
                    if (filled < FRAME_SAMPLES) continue

                    val frame = FloatArray(FRAME_SAMPLES) { i -> pcm[i] / 32768f }
                    val result = trySend(frame)
                    if (result.isClosed) return@thread
                    check(result.isSuccess) { "Microphone frame queue overflow; utterance incomplete" }
                }
            } catch (e: Exception) {
                if (running.get()) close(e)
            }
        }

        awaitClose {
            running.set(false)
            // Unblock a blocking read before joining; never release a recorder still in use.
            runCatching { recorder.stop() }
            runCatching { worker.join() }
            runCatching { recorder.release() }
            Log.i(TAG, "Capture stopped")
        }
    }.flowOn(Dispatchers.IO)
}
