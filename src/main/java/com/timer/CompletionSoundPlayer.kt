package com.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.annotation.RawRes

/**
 * Plays the completion sound once and owns the [MediaPlayer] lifecycle.
 *
 * Uses alarm audio attributes, so the sound follows the alarm volume (like the system clock app)
 * instead of the media volume, which is often muted.
 */
internal class CompletionSoundPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /**
     * Plays [soundRes] at [volume] (clamped to `0f..1f`) and invokes [onFinished] when playback ends.
     * [onFinished] is invoked immediately if the sound cannot be played.
     */
    fun play(@RawRes soundRes: Int, volume: Float, onFinished: () -> Unit = {}) {
        release()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sessionId = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        val created = MediaPlayer.create(context, soundRes, attributes, sessionId)
        if (created == null) {
            onFinished()
            return
        }
        val level = volume.coerceIn(0f, 1f)
        player = created.apply {
            setVolume(level, level)
            setOnCompletionListener { finished ->
                if (player === finished) release() else finished.release()
                onFinished()
            }
            start()
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}
