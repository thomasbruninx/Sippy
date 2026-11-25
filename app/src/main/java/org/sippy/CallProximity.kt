package org.sippy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Android blanks the display and blocks touch while its proximity sensor is near. */
internal class CallProximity(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val lock = if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
        power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Sippy:call-proximity")
            .apply { setReferenceCounted(false) }
    } else null
    private var enabled = false
    private val renew = object : Runnable {
        override fun run() {
            if (!enabled) return
            runCatching { lock?.acquire(120_000L) }.onFailure { release() }
            if (enabled) handler.postDelayed(this, 60_000L)
        }
    }

    fun update(earpieceCall: Boolean) {
        if (enabled == earpieceCall || lock == null) return
        enabled = earpieceCall
        if (enabled) renew.run() else release()
    }

    fun release() {
        enabled = false
        handler.removeCallbacks(renew)
        lock?.let { if (it.isHeld) runCatching { it.release() } }
    }
}
