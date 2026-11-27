package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Verify the actual dialogs without submitting a transfer or changing the SIP account. */
internal class TransferModeChecks(private val instrumentation: Instrumentation) {
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun texts(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Set<String> {
        if (node == null) return emptySet()
        return buildSet {
            node.text?.toString()?.let { add(it) }
            for (i in 0 until node.childCount) addAll(texts(node.getChild(i)))
        }
    }
    private fun await(message: String, predicate: (Set<String>) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(texts())) return
            Thread.sleep(40)
        }
        error(message)
    }
    private fun dismiss() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun tap(text: String) {
        val node = instrumentation.uiAutomation.rootInActiveWindow
            .findAccessibilityNodeInfosByText(text).first { it.text?.toString() == text }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        }
    }
    fun run() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefs = activity.prefs
        val previous = prefs.transferMode
        val transfer = MainActivity::class.java.getDeclaredMethod("transferMenu", Long::class.javaPrimitiveType).apply { isAccessible = true }
        fun show(mode: TransferMode) = main { prefs.transferMode = mode; transfer.invoke(activity, -1L) }
        fun destination(title: String) = await("Missing $title destination picker") {
            title in it && "Dialpad" in it && "Phonebooks" in it && "Transfer call" !in it
        }
        try {
            prefs.transferMode = TransferMode.BOTH
            check(Preferences(instrumentation.targetContext).transferMode == TransferMode.BOTH) { "Existing users must retain both transfer modes" }
            show(TransferMode.DIRECT_ONLY)
            check(Preferences(instrumentation.targetContext).transferMode == TransferMode.DIRECT_ONLY)
            destination("Direct transfer")
            dismiss()
            show(TransferMode.ATTENDED_ONLY)
            destination("Ask first")
            dismiss()
            for (choice in listOf("Direct transfer", "Ask first")) {
                show(TransferMode.BOTH)
                await("Both must offer a transfer mode choice") {
                    "Transfer call" in it && "Direct transfer" in it && "Ask first" in it && "Dialpad" !in it
                }
                tap(choice)
                destination(choice)
                dismiss()
            }
        } finally {
            main {
                prefs.transferMode = previous
                activity.finish()
            }
        }
    }
}
