package org.sippy

import android.content.Context
import android.net.Uri
import java.io.File

/** Reset local data without invoking Android's clear-app-data API (which also loses policy access/state). */
internal class FactoryReset(context: Context) {
    private val context = context.applicationContext

    fun run(service: SipService, result: (Result<Unit>) -> Unit) {
        fun complete(outcome: Result<Unit>) {
            // Always release the reset guard, even if scheduling a managed sync fails.
            val resumed = runCatching { service.finishFactoryReset() }
            val scheduled = runCatching { Directory(context).schedule() }
            result(if (outcome.isFailure) outcome else if (resumed.isFailure) resumed else scheduled)
        }
        try { service.prepareFactoryReset() }
        catch (failure: Exception) { complete(Result.failure(failure)); return }
        // Wait behind imports/sync work already queued by the UI. A changed revision
        // also prevents a previously running JobService download from restoring stale data.
        directoryExecutor.execute {
            val cleared = runCatching {
                clearLocalData(context)
            }
            context.mainExecutor.execute {
                if (cleared.isFailure) complete(cleared)
                else ActivityStore.get(context).factoryReset(::complete)
            }
        }
    }

    companion object {
        internal fun clearLocalData(context: Context) {
            val prefs = Preferences(context)
            val managedFiles = prefs.accounts().filter { it.managed && "ringtoneUri" in it.managedKeys }
                .mapNotNull { it.options.ringtoneUri?.let(Uri::parse) }
                .filter { it.scheme == "file" }.mapNotNull { it.path?.let(::File)?.canonicalPath }.toSet()
            prefs.resetLocalSettings()
            context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("ringtone-") && it.canonicalPath !in managedFiles }.forEach {
                check(it.delete() || !it.exists()) { "Could not remove a custom ringtone." }
            }
            fun clearCache(file: File) {
                if (file.canonicalPath in managedFiles) return
                if (file.isDirectory && managedFiles.any { it.startsWith(file.canonicalPath + File.separator) }) {
                    file.listFiles().orEmpty().forEach(::clearCache)
                } else check(file.deleteRecursively() || !file.exists()) { "Could not clear temporary files." }
            }
            context.cacheDir.listFiles().orEmpty().forEach(::clearCache)
        }
    }
}
