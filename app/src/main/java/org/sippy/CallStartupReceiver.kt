package org.sippy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = Preferences(context)
        if (!prefs.keepAvailable || intent.action == Intent.ACTION_BOOT_COMPLETED && !prefs.startOnBoot) return
        runCatching {
            context.startForegroundService(Intent(context, SipService::class.java).setAction(SipService.KEEP_AVAILABLE))
        }.onFailure {
            ActivityStore.get(context).event(EventKind.WARNING,
                "Background calling could not start automatically. Open Sippy to reconnect.")
        }
    }
}
