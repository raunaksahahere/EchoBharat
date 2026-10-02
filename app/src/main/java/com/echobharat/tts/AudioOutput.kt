package com.echobharat.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Plays PCM serially. Alerts use the alarm stream and cannot be stopped by normal speech. */
class AudioOutput(private val context: Context) {

    companion object {
        private const val TAG = "AudioOutput"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val current = AtomicReference<AudioTrack?>(null)
    private val playbackLock = Mutex()

    @Volatile
    private var alertPlaying = false

    /** Returns only after the written frames have actually played, or playback has failed. */
    suspend fun play(samples: FloatArray, sampleRate: Int, alert: Boolean): Boolean =
        playbackLock.withLock {
            withContext(Dispatchers.IO) {
                if (samples.isEmpty() || sampleRate <= 0) return@withContext false

                val streamType = if (alert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
                var previousVolume: Int? = null
                var track: AudioTrack? = null
                alertPlaying = alert
                try {
                    if (alert) {
                        previousVolume = runCatching { audioManager.getStreamVolume(streamType) }.getOrNull()
                        runCatching {
                            audioManager.setStreamVolume(streamType, audioManager.getStreamMaxVolume(streamType), 0)
                        }.onFailure {
                            Log.w(TAG, "Could not raise alarm volume: ${it.message}")
                        }
                    }

                    val minBuffer = AudioTrack.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                    )
                    if (minBuffer <= 0) {
                        Log.e(TAG, "AudioTrack.getMinBufferSize failed: $minBuffer")
                        return@withContext false
                    }
                    val active = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
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
                        // Bound the native streaming buffer, not a second copy of the entire utterance.
                        .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 10 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track = active
                    current.set(active)
                    active.play()
                    var offset = 0
                    while (offset < samples.size) {
                        ensureActive()
                        if (current.get() !== active) return@withContext false
                        val written = active.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) {
                            Log.e(TAG, "AudioTrack.write returned $written")
                            return@withContext false
                        }
                        offset += written
                    }

                    // write() only queues frames. stop()+release() here discards the buffered tail.
                    val durationMs = samples.size * 1000L / sampleRate
                    val deadline = System.nanoTime() + (durationMs + 2_000L) * 1_000_000L
                    while ((active.playbackHeadPosition.toLong() and 0xffffffffL) < offset) {
                        if (current.get() !== active) return@withContext false
                        if (System.nanoTime() >= deadline) {
                            Log.e(TAG, "PLAYBACK_DRAIN_TIMEOUT: $offset frames queued")
                            return@withContext false
                        }
                        delay(10)
                    }
                    Log.i(TAG, "Played ${durationMs}ms (${if (alert) "ALERT" else "normal"}) at ${sampleRate}Hz")
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "PLAYBACK_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                    false
                } finally {
                    track?.let {
                        current.compareAndSet(it, null)
                        runCatching { it.stop() }
                        runCatching { it.release() }
                    }
                    if (alert) {
                        previousVolume?.let { v ->
                            runCatching { audioManager.setStreamVolume(streamType, v, 0) }
                        }
                    }
                    alertPlaying = false
                }
            }
        }

    /** Stops normal playback. Its owner alone releases the track, including on failure. */
    fun stop() {
        if (alertPlaying) {
            Log.i(TAG, "stop() ignored during alert playback")
            return
        }
        current.getAndSet(null)?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
    }
}
