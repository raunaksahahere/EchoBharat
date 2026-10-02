package com.echobharat.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays synthesised PCM.
 *
 * Normal speech is interruptible — a newer message replaces whatever is playing. Alert
 * playback is not: it takes the alarm stream at maximum volume and refuses to be cut
 * short by ordinary messages (PRD §3.7).
 */
class AudioOutput(private val context: Context) {

    companion object {
        private const val TAG = "AudioOutput"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val current = AtomicReference<AudioTrack?>(null)

    @Volatile
    private var alertPlaying = false

    /**
     * Plays [samples] and suspends until playback finishes.
     *
     * @param alert routes to the alarm stream at max volume and blocks interruption.
     * @return false when playback was refused or failed.
     */
    suspend fun play(samples: FloatArray, sampleRate: Int, alert: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (samples.isEmpty()) return@withContext false

            if (alertPlaying && !alert) {
                Log.i(TAG, "Not interrupting an alert with normal speech")
                return@withContext false
            }

            stopCurrent()

            val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            val streamType = if (alert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC

            var previousVolume: Int? = null
            if (alert) {
                previousVolume = runCatching { audioManager.getStreamVolume(streamType) }.getOrNull()
                runCatching {
                    audioManager.setStreamVolume(
                        streamType,
                        audioManager.getStreamMaxVolume(streamType),
                        0
                    )
                }.onFailure {
                    // DND or a restricted profile can refuse this; play anyway.
                    Log.w(TAG, "Could not raise alarm volume: ${it.message}")
                }
            }

            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "AudioTrack.getMinBufferSize failed: $minBuffer")
                return@withContext false
            }
            val bufferBytes = maxOf(minBuffer, samples.size * 4)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "AUDIOTRACK_INIT_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext false
            }

            current.set(track)
            if (alert) alertPlaying = true

            try {
                track.play()
                var offset = 0
                while (offset < samples.size) {
                    val written = track.write(
                        samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) {
                        Log.e(TAG, "AudioTrack.write returned $written")
                        break
                    }
                    offset += written
                }

                // MODE_STREAM keeps the tail buffered; drain before tearing down.
                runCatching { track.stop() }
                val durationMs = (samples.size * 1000L) / sampleRate
                Log.i(TAG, "Played ${durationMs}ms (${if (alert) "ALERT" else "normal"}) at ${sampleRate}Hz")
                true
            } catch (e: Exception) {
                Log.e(TAG, "PLAYBACK_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                false
            } finally {
                runCatching { track.release() }
                current.compareAndSet(track, null)
                if (alert) {
                    alertPlaying = false
                    previousVolume?.let { v ->
                        runCatching { audioManager.setStreamVolume(streamType, v, 0) }
                    }
                }
            }
        }

    /** Stops normal playback. An in-flight alert is left alone. */
    fun stop() {
        if (alertPlaying) {
            Log.i(TAG, "stop() ignored during alert playback")
            return
        }
        stopCurrent()
    }

    private fun stopCurrent() {
        current.getAndSet(null)?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }
}
