package org.sippy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.CheckBox
import java.io.File
import java.util.concurrent.Executors

internal class SettingsFiles(private val activity: MainActivity) {
    var includePassword = false
    private val prefs get() = activity.prefs
    private val worker = Executors.newSingleThreadExecutor()
    val ringtonePreview = RingtonePreview(activity) { activity.message("This ringtone could not be played. Try choosing it again.") }

    fun close() { ringtonePreview.stop(); ringtonePreview.onChange = null; worker.shutdown() }

    fun ringtoneLabel() = if (prefs.data.getString("ringtoneUri", null) == null) "Default ringtone"
        else prefs.data.getString("ringtoneName", "Custom ringtone").orEmpty()

    fun toggleRingtonePreview() {
        if (!idle()) return
        val uri = prefs.data.getString("ringtoneUri", null)?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtonePreview.toggle(uri)
    }

    fun chooseRingtone() {
        if (!idle()) return
        if (prefs.isManaged("ringtoneUri")) return activity.message("The ringtone is managed by your organization.")
        ringtonePreview.stop()
        AlertDialog.Builder(activity).setTitle("Incoming ringtone")
            .setItems(arrayOf("Default ringtone", "Android ringtones", "Choose WAV or MP3 file")) { _, item ->
                when (item) {
                    0 -> saveRingtone(null, "Default ringtone")
                    1 -> open(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            Uri.parse(prefs.data.getString("ringtoneUri", null)
                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString())), RINGTONE)
                    2 -> open(document(Intent.ACTION_OPEN_DOCUMENT, "audio/*"), AUDIO)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    var exportAccountId: String? = null

    fun export() {
        val password = CheckBox(activity).apply {
            text = if (prefs.isManaged("password")) "Password managed by your organization" else "Include SIP password"
            isEnabled = !prefs.isManaged("password")
        }
        AlertDialog.Builder(activity).setTitle("Export saved SIP settings")
            .setMessage("Exports the saved account, network settings, and codec order. If included, the password is readable in the JSON file.")
            .setView(password).setPositiveButton("Choose file") { _, _ ->
                includePassword = password.isChecked
                exportAccountId = prefs.selectedAccount().id
                val filename = prefs.selectedAccount().name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().ifEmpty { "Sippy-account" }
                open(document(Intent.ACTION_CREATE_DOCUMENT, "application/json")
                    .putExtra(Intent.EXTRA_TITLE, "$filename.json"), EXPORT)
            }.setNegativeButton("Cancel", null).show()
    }

    fun import() {
        if (idle()) open(document(Intent.ACTION_OPEN_DOCUMENT, "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), IMPORT)
    }

    fun exportLocalContacts() = open(document(Intent.ACTION_CREATE_DOCUMENT, "text/csv")
        .putExtra(Intent.EXTRA_TITLE, "Sippy-local-contacts.csv"), LOCAL_EXPORT)

    @Suppress("DEPRECATION")
    private fun open(intent: Intent, code: Int) {
        runCatching { activity.startActivityForResult(intent, code) }
            .onFailure { activity.message("No app is available to select this file or ringtone.") }
    }

    private fun document(action: String, type: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type)

    @Suppress("DEPRECATION")
    fun result(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in setOf(RINGTONE, AUDIO, EXPORT, IMPORT, LOCAL_EXPORT)) return false
        if (result != Activity.RESULT_OK) return true
        if (code == RINGTONE) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return true
            val name = runCatching { RingtoneManager.getRingtone(activity, uri)?.getTitle(activity) }.getOrNull()
            saveRingtone(uri.toString(), name ?: "Android ringtone")
            return true
        }
        val uri = data?.data ?: return true
        when (code) {
            LOCAL_EXPORT -> work("Could not export local contacts.") {
                val entries = activity.directory.stored(Source.LOCAL)
                val csv = localContactsCsv(entries)
                requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(csv.toByteArray(Charsets.UTF_8))
                }
                val finish: () -> Unit = { activity.message("Exported ${entries.size} local contacts.") }
                finish
            }
            AUDIO -> importAudio(uri)
            EXPORT -> {
                val account = prefs.accounts().firstOrNull { it.id == exportAccountId } ?: if (exportAccountId == null) prefs.selectedAccount() else {
                    activity.message("The selected account is no longer available. Export it again.")
                    includePassword = false; exportAccountId = null
                    return true
                }
                val json = SipSettingsJson.encode(account.profile, prefs.codecs(), includePassword && "password" !in account.managedKeys, account.name, account.dnd)
                exportAccountId = null
                includePassword = false
                work("Could not export SIP settings.") {
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    val finish: () -> Unit = { activity.message("SIP settings exported.") }
                    finish
                }
            }
            IMPORT -> work("Could not import settings. Choose a valid Sippy settings JSON file.") {
                val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                    val buffer = ByteArray(262145)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read == -1) break
                        count += read
                    }
                    buffer.copyOf(count)
                }
                require(bytes.size <= 262144)
                val backup = SipSettingsJson.decode(bytes.toString(Charsets.UTF_8))
                val finish: () -> Unit = { confirmImport(backup) }
                finish
            }
        }
        return true
    }

    private fun importAudio(uri: Uri) = work("Could not use this audio file. Choose a playable WAV or MP3 file under 20 MB.") {
        val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Custom ringtone"
        require(name.endsWith(".wav", true) || name.endsWith(".mp3", true))
        val file = File.createTempFile("ringtone-", if (name.endsWith(".wav", true)) ".wav" else ".mp3", activity.filesDir)
        try {
            requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= 20 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val player = MediaPlayer()
            try { player.setDataSource(file.absolutePath); player.prepare(); require(player.duration > 0) }
            finally { player.release() }
        } catch (error: Exception) { file.delete(); throw error }
        val finish: () -> Unit = {
            if (!saveRingtone(Uri.fromFile(file).toString(), name)) file.delete()
        }
        finish
    }

    private fun confirmImport(backup: SipSettingsBackup) {
        if (!idle()) return
        val p = backup.profile
        val name = activity.ui.field("Account name", backup.name.orEmpty())
        val fields = activity.ui.column(10).apply {
            activity.ui.pad(this, 20)
            addView(activity.ui.text("Import as a new named account. Existing accounts are kept. Device media settings and codec order are shared and will be updated from this file.", 14))
            addView(activity.ui.labelled("Account name", name))
            if (p.password.isEmpty()) addView(activity.ui.text("This file has no password. Enter it after importing.", 13, Palette.secondary))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Import SIP account")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Import", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (idle()) runCatching {
                val available = activity.sipState.availableCodecs
                require(available.isEmpty() || backup.codecs.any { it.enabled && it.id in available }) { "Enable at least one supported codec." }
                prefs.addAccount(name.text.toString(), p, backup.dnd)
                prefs.save(p)
                prefs.saveCodecs(backup.codecs)
                activity.sip?.configure()
                dialog.dismiss()
                activity.renderPage()
                activity.message("SIP account imported.")
            }.onFailure { name.error = it.localizedMessage ?: "Could not import account." }
        }
    }

    private fun saveRingtone(uri: String?, name: String): Boolean {
        if (!idle()) return false
        if (prefs.isManaged("ringtoneUri")) return false
        ringtonePreview.stop()
        val previous = prefs.data.getString("ringtoneUri", null)
        prefs.data.edit().putString("ringtoneUri", uri).putString("ringtoneName", name).apply()
        activity.sip?.updateRingtone()
        if (previous != uri && previous?.startsWith("file:") == true) {
            val file = File(Uri.parse(previous).path.orEmpty())
            if (file.parentFile == activity.filesDir && file.name.startsWith("ringtone-")) file.delete()
        }
        activity.renderPage()
        activity.message("Ringtone saved.")
        return true
    }

    private fun idle(): Boolean {
        if (activity.sipState.lines.isEmpty()) return true
        activity.message("End all calls before changing these settings.")
        return false
    }

    private fun work(error: String, action: () -> (() -> Unit)) {
        worker.execute {
            val outcome = runCatching(action)
            activity.runOnUiThread {
                if (!activity.isDestroyed) outcome.onSuccess { it() }.onFailure { activity.message(error) }
            }
        }
    }

    companion object {
        private const val RINGTONE = 40
        private const val AUDIO = 41
        private const val EXPORT = 42
        private const val IMPORT = 43
        private const val LOCAL_EXPORT = 44
    }
}
