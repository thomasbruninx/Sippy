package org.sippy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Owns all incoming-call alerts, including the system default ringtone. */
internal class IncomingRingtone(private val context: Context) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var vibrating = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    @Suppress("DEPRECATION")
    fun start(uri: Uri? = null, vibrate: Boolean = false) {
        stop()
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        if (vibrate) requestVibration()
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            play(uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), fallback = uri != null)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        if (!enabled) stopVibration()
        else if (!vibrating) requestVibration()
    }

    @Suppress("DEPRECATION")
    private fun requestVibration(): String? {
        if (context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return "The phone is in silent mode. Switch to Sound or Vibrate for call vibration."
        }
        return runCatching {
            // Query at the time of the alert, using the manager on newer devices.
            val device = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else context.getSystemService(Vibrator::class.java)
            check(device?.hasVibrator() == true) { "No phone vibrator is available." }
            vibrator = device
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 1000, 700), intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0),
                0)
            if (Build.VERSION.SDK_INT >= 33) {
                device.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_RINGTONE).build())
            } else {
                val vibrationAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                device.vibrate(effect, vibrationAttributes)
            }
            vibrating = true
            ActivityStore.get(context.applicationContext).event(EventKind.INFO,
                "Incoming call vibration requested")
            null
        }.getOrElse { failure ->
            ActivityStore.get(context.applicationContext).event(EventKind.WARNING,
                "Could not start call vibration (${failure.javaClass.simpleName})")
            "Could not start vibration. Check the phone's vibration settings and Sippy's event log."
        }
    }

    private fun stopVibration() {
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
        vibrator = null
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
        stopVibration()
    }

    private fun stopAudio() {
        player?.let { player = null; it.release() }
    }
}
