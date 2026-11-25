package org.sippy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

internal class RingtonePreview(private val context: Context, private val onError: () -> Unit) {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var source: Uri? = null
    var onChange: (() -> Unit)? = null
    var isPlaying = false
        private set

    fun toggle(uri: Uri) {
        if (source != uri || player == null) {
            stop()
            val next = MediaPlayer()
            player = next
            source = uri
            isPlaying = true
            onChange?.invoke()
            runCatching {
                next.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                next.isLooping = true
                next.setDataSource(context, uri)
                next.setOnPreparedListener {
                    if (player === it) {
                        prepared = true
                        if (isPlaying) runCatching { it.start() }.onFailure { fail(next) }
                    }
                }
                next.setOnErrorListener { _, _, _ -> fail(next); true }
                next.prepareAsync()
            }.onFailure { fail(next) }
        } else {
            runCatching {
                isPlaying = !isPlaying
                if (prepared) {
                    if (isPlaying) player?.start() else player?.pause()
                }
                onChange?.invoke()
            }.onFailure { player?.let(::fail) }
        }
    }

    private fun fail(failed: MediaPlayer) {
        if (player !== failed) return
        stop()
        onError()
    }

    fun stop() {
        player?.let { player = null; it.release() }
        source = null
        prepared = false
        isPlaying = false
        onChange?.invoke()
    }
}
