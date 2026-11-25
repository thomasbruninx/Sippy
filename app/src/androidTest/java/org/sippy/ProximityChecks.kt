package org.sippy

import android.app.Instrumentation
import android.os.Bundle
import android.os.PowerManager
import java.io.File

internal class ProximityChecks(private val instrumentation: Instrumentation) {
    fun run() {
        val protection = CallProximity(instrumentation.targetContext)
        val field = CallProximity::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(protection) as? PowerManager.WakeLock
        File(instrumentation.targetContext.cacheDir, "proximity-test-supported").writeText((lock != null).toString())
        if (lock == null) {
            instrumentation.runOnMainSync { protection.update(true); protection.release() }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Proximity hardware is unavailable; safe fallback verified.\n") })
            return
        }
        try {
            instrumentation.runOnMainSync { protection.update(true) }
            check(lock.isHeld)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Proximity controller enabled for near/far sensor test.\n") })
            Thread.sleep(8000)
            instrumentation.runOnMainSync { protection.update(false) }
            check(!lock.isHeld)
            instrumentation.runOnMainSync { protection.update(true); protection.release() }
            check(!lock.isHeld)
        } finally {
            instrumentation.runOnMainSync { protection.release() }
        }
    }
}
