package com.echobharat.tts

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class AudioOutputTest {
    private val manager = mock<AudioManager> {
        on { getStreamVolume(AudioManager.STREAM_ALARM) } doReturn 2
        on { getStreamMaxVolume(AudioManager.STREAM_ALARM) } doReturn 7
    }
    private val context = mock<Context> {
        on { getSystemService(Context.AUDIO_SERVICE) } doReturn manager
    }

    private suspend fun withTrack(track: AudioTrack, minBuffer: Int = 256, block: suspend () -> Unit) {
        Mockito.mockStatic(AudioTrack::class.java).use { statics ->
            statics.`when`<Int> { AudioTrack.getMinBufferSize(any(), any(), any()) }.thenReturn(minBuffer)
            Mockito.mockConstruction(AudioAttributes.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
                Mockito.mockConstruction(AudioFormat.Builder::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
                    Mockito.mockConstruction(AudioTrack.Builder::class.java,
                        Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
                        whenever(builder.build()).thenReturn(track)
                    }.use { block() }
                }
            }
        }
    }

    @Test fun `play waits for playback head before releasing buffered audio`() = runBlocking(Dispatchers.IO) {
        val track = mock<AudioTrack>()
        whenever(track.write(any<FloatArray>(), any(), any(), any())).thenReturn(160)
        var headReads = 0
        whenever(track.playbackHeadPosition).thenAnswer { headReads++; if (headReads < 3) 0 else 160 }
        withTrack(track) {
            assertTrue(AudioOutput(context).play(FloatArray(160), 16000, false))
            assertTrue("Playback was released before its buffered tail drained", headReads >= 3)
            verify(track).release()
        }
    }

    @Test fun `failed writes do not report successful playback`() = runBlocking(Dispatchers.IO) {
        val track = mock<AudioTrack>()
        whenever(track.write(any<FloatArray>(), any(), any(), any())).thenReturn(AudioTrack.ERROR_DEAD_OBJECT)
        withTrack(track) {
            assertFalse(AudioOutput(context).play(FloatArray(160), 16000, false))
        }
    }

    @Test fun `alarm volume restored if initialization fails`() = runBlocking(Dispatchers.IO) {
        withTrack(mock(), minBuffer = AudioTrack.ERROR_BAD_VALUE) {
            assertFalse(AudioOutput(context).play(FloatArray(160), 16000, true))
            verify(manager).setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        }
    }
}
