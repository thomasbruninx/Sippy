package org.sippy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator

/** Owns all incoming-call alerts, including the system default ringtone. */
internal class IncomingRingtone(private val context: Context) {
    private var player: MediaPlayer? = null
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var vibrating = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    @Suppress("DEPRECATION")
    fun start(uri: Uri? = null, vibrate: Boolean = false) {
        stop()
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        if (vibrate && mode != AudioManager.RINGER_MODE_SILENT && vibrator?.hasVibrator() == true) {
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0), attributes)
                vibrating = true
            }
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            play(uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), fallback = uri != null)
        }
    }

    private fun play(uri: Uri, fallback: Boolean) {
        val next = MediaPlayer()
        player = next
        fun failed() {
            if (player !== next) return
            stopAudio()
            if (fallback) play(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), false)
        }
        runCatching {
            next.setAudioAttributes(attributes)
            next.isLooping = true
            next.setDataSource(context, uri)
            next.setOnPreparedListener { if (player === it) runCatching { it.start() }.onFailure { failed() } }
            next.setOnErrorListener { _, _, _ -> failed(); true }
            next.prepareAsync()
        }.onFailure { failed() }
    }

    fun stop() {
        stopAudio()
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }

    private fun stopAudio() {
        player?.let { player = null; it.release() }
    }
}
