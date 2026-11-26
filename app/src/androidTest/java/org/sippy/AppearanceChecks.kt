package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class AppearanceChecks(private val instrumentation: Instrumentation) {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readLine().orEmpty() }

    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color))
        }
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }

    fun run() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val original = activity.prefs.appearance
        val originalSystem = shell("cmd uimode night").substringAfter("Night mode:").trim()
        fun setSystem(dark: Boolean) {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            fun matches() = main {
                (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark
            }
            val deadline = System.currentTimeMillis() + 5000
            while (!matches() && System.currentTimeMillis() < deadline) Thread.sleep(25)
            check(matches()) { "System appearance change did not arrive" }
        }
        fun verify(dark: Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (main { Palette.isDark != dark } && System.currentTimeMillis() < deadline) Thread.sleep(25)
            main {
                check(Palette.isDark == dark) { "Incorrect effective appearance" }
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                check((root.background as ColorDrawable).color == Palette.background)
                for ((text, fill) in listOf(Palette.text to Palette.background, Palette.text to Palette.surface,
                    Palette.secondary to Palette.background, Palette.accent to Palette.soft,
                    Palette.onAccent to Palette.accent, Palette.danger to Palette.dangerSoft,
                    Palette.onDanger to Palette.danger, Palette.warning to Palette.surface)) {
                    check(contrast(text, fill) >= 4.5) { "Insufficient text contrast in ${if (dark) "dark" else "light"} mode" }
                }
            }
        }
        try {
            main { activity.setAppearance(Appearance.LIGHT) }; verify(false)
            setSystem(true); verify(false)
            main { activity.setAppearance(Appearance.DARK) }; verify(true)
            setSystem(false); verify(true)
            main { activity.setAppearance(Appearance.SYSTEM) }
            verify(false)
            setSystem(true); verify(true)
            setSystem(false); verify(false)
            check(Preferences(instrumentation.targetContext).appearance == Appearance.SYSTEM)
        } finally {
            shell("cmd uimode night $originalSystem")
            instrumentation.waitForIdleSync()
            main { activity.setAppearance(original); activity.finish() }
            val deadline = System.currentTimeMillis() + 15000
            while (!main { activity.isDestroyed } && System.currentTimeMillis() < deadline) Thread.sleep(25)
            check(main { activity.isDestroyed }) { "Appearance test activity did not close" }
        }
    }
}
