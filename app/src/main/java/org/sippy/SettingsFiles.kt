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
    private var pendingWork = 0
    val busy get() = pendingWork > 0
    val ringtonePreview = RingtonePreview(activity) { activity.message("This ringtone could not be played. Try choosing it again.") }

    fun close() { ringtonePreview.stop(); ringtonePreview.onChange = null; worker.shutdown() }

    fun ringtoneLabel() = if (prefs.selectedAccount().options.ringtoneUri == null) "Default ringtone"
        else prefs.selectedAccount().options.ringtoneName

    fun toggleRingtonePreview() {
        if (!idle()) return
        val uri = prefs.selectedAccount().options.ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtonePreview.toggle(uri)
    }

    var ringtoneAccountId: String? = null

    fun chooseRingtone() {
        if (!idle()) return
        ringtoneAccountId = prefs.selectedAccount().id
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
                            Uri.parse(prefs.selectedAccount().options.ringtoneUri
                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString())), RINGTONE)
                    2 -> open(document(Intent.ACTION_OPEN_DOCUMENT, "audio/*"), AUDIO)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    var exportAccountId: String? = null // Retained across pending document requests from older builds.
    var backupSections: Set<BackupSection> = BackupSection.entries.toSet() - BackupSection.PASSWORDS
    private val backup get() = SettingsBackup(activity, prefs)

    fun export() {
        if (!idle()) return
        chooseSections("Export settings", BackupSection.entries.toSet(), backupSections) { chosen ->
            backupSections = chosen
            includePassword = BackupSection.PASSWORDS in chosen
            open(document(Intent.ACTION_CREATE_DOCUMENT, "application/json").putExtra(Intent.EXTRA_TITLE, "Sippy-settings-backup.json"), EXPORT)
        }
    }

    fun import() {
        if (idle()) open(document(Intent.ACTION_OPEN_DOCUMENT, "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), IMPORT)
    }

    private fun chooseSections(title: String, available: Set<BackupSection>, selected: Set<BackupSection>, legacy: org.json.JSONObject? = null,
        confirmed: (Set<BackupSection>) -> Unit) {
        val content = activity.ui.column(10).apply {
            activity.ui.pad(this, 18)
            addView(activity.ui.text("Choose what to include. Imports merge matching accounts and keep other accounts. MDM-managed fields are skipped. Managed passwords are never exported. Contacts, history, logs, Android permissions and certificate private keys are not included.", 14))
        }
        val accountName = if (legacy?.optBoolean("legacy") == true) activity.ui.field("Account name", legacy.getJSONArray("accounts").getJSONObject(0).getString("name")) else null
        if (accountName != null) content.addView(activity.ui.labelled("Account name", accountName))
        val checks = available.associateWith { section -> CheckBox(activity).apply {
            text = section.label; isChecked = section in selected
            setTextColor(Palette.text)
        }.also(content::addView) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(activity.scroll(content))
            .setNegativeButton("Cancel", null).setPositiveButton(if (title.startsWith("Import")) "Import" else "Choose file", null).show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * .85f).toInt())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val choices = checks.filterValues { it.isChecked }.keys
            if (choices.none { it != BackupSection.PASSWORDS }) { activity.message("Select at least one settings category."); return@setOnClickListener }
            if (accountName != null) {
                val name = runCatching { SipAccountFields.name(accountName.text.toString()) }.getOrElse { accountName.error = "Enter an account name."; return@setOnClickListener }
                legacy!!.getJSONArray("accounts").getJSONObject(0).put("name", name)
            }
            if (!idle()) return@setOnClickListener
            dialog.dismiss(); confirmed(choices)
        }
    }

    private fun resultDialog(title: String, message: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
    }

    fun exportLocalContacts() = open(document(Intent.ACTION_CREATE_DOCUMENT, "text/csv")
        .putExtra(Intent.EXTRA_TITLE, "Sippy-local-contacts.csv"), LOCAL_EXPORT)

    fun exportEvents() = open(document(Intent.ACTION_CREATE_DOCUMENT, "text/csv")
        .putExtra(Intent.EXTRA_TITLE, "Sippy-event-log.csv"), EVENT_EXPORT)

    @Suppress("DEPRECATION")
    private fun open(intent: Intent, code: Int) {
        runCatching { activity.startActivityForResult(intent, code) }
            .onFailure { activity.message("No app is available to select this file or ringtone.") }
    }

    private fun document(action: String, type: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type)

    @Suppress("DEPRECATION")
    fun result(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in setOf(RINGTONE, AUDIO, EXPORT, IMPORT, LOCAL_EXPORT, EVENT_EXPORT)) return false
        if (result != Activity.RESULT_OK) return true
        if (code == RINGTONE) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return true
            val name = runCatching { RingtoneManager.getRingtone(activity, uri)?.getTitle(activity) }.getOrNull()
            saveRingtone(uri.toString(), name ?: "Android ringtone")
            return true
        }
        val uri = data?.data ?: return true
        when (code) {
            EVENT_EXPORT -> {
                pendingWork++
                ActivityStore.get(activity).exportEvents({ requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")) }) { outcome ->
                    pendingWork--
                    if (!activity.isDestroyed) outcome.onSuccess { resultDialog("Export successful", "Exported $it events as CSV.") }
                        .onFailure { resultDialog("Export failed", "Could not export the event log. Check the destination and available storage.") }
                }
            }
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
                val sections = if (includePassword) backupSections + BackupSection.PASSWORDS else backupSections - BackupSection.PASSWORDS
                includePassword = false
                work("Could not export settings. Check the destination and available storage.", resultModal = true) {
                    val result = backup.export(sections)
                    val bytes = result.root.toString(2).toByteArray(Charsets.UTF_8)
                    require(bytes.size <= SettingsBackup.MAX_BYTES) { "Backup is too large." }
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    val finish: () -> Unit = { resultDialog("Export successful", "Selected settings exported." + result.notes.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n\n").orEmpty()) }
                    finish
                }
            }
            IMPORT -> work("Could not read settings. Select a supported Sippy JSON backup under 128 MB.", resultModal = true) {
                val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count == -1) break
                        require(output.size() + count <= SettingsBackup.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val root = SettingsBackup.read(bytes.toString(Charsets.UTF_8))
                val available = SettingsBackup.available(root)
                val finish: () -> Unit = {
                    chooseSections("Import settings", available, available - BackupSection.PASSWORDS, root) { chosen ->
                        if (idle()) {
                            // Preparation reads/validates ringtone audio on the worker; preference changes are serialized on the UI thread.
                            var preparedFiles = emptyList<File>()
                            work("Could not prepare selected settings. The backup contains invalid or unavailable ringtone audio.", resultModal = true,
                                abandoned = { preparedFiles.forEach { it.delete() } }) {
                                val prepared = backup.prepareAudio(root, chosen)
                                preparedFiles = prepared.second
                                val apply: () -> Unit = {
                                    try {
                                        if (idle()) {
                                            val result = backup.restore(prepared.first, chosen)
                                            val followUp = mutableListOf<String>()
                                            runCatching { activity.sip?.configure(); activity.updateBackgroundCalling() }
                                                .onFailure { followUp.add("Settings saved, but SIP background reception could not be restarted. Reopen Sippy and check permissions.") }
                                            runCatching { activity.directory.schedule() }
                                                .onFailure { followUp.add("Settings saved, but phonebook synchronization could not be scheduled. Open Phonebook settings to retry.") }
                                            runCatching { ActivityStore.get(activity).prune() }
                                                .onFailure { followUp.add("Settings saved, but event-log cleanup could not run.") }
                                            activity.refreshAppearance()
                                            activity.renderPage()
                                            val completed = result.copy(notes = result.notes + followUp)
                                            resultDialog(if (result.skipped.isEmpty() && followUp.isEmpty()) "Import successful" else "Import completed with details", completed.text())
                                        } else resultDialog("Import cancelled", "A call started before settings could be applied. No settings were changed. End the call and import again.")
                                    } catch (failure: Exception) {
                                        resultDialog("Import failed", failure.message ?: "Settings could not be restored.")
                                    } finally {
                                        val used = prefs.accounts().mapNotNull { it.options.ringtoneUri }.toSet()
                                        prepared.second.filter { android.net.Uri.fromFile(it).toString() !in used }.forEach { it.delete() }
                                    }
                                }
                                apply
                            }
                        }
                    }
                }
                finish
            }
        }
        return true
    }

    private fun importAudio(uri: Uri) {
        val accountId = ringtoneAccountId ?: prefs.selectedAccount().id
        work("Could not use this audio file. Choose a playable WAV or MP3 file under 20 MB.") {
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
            if (!saveRingtone(Uri.fromFile(file).toString(), name, accountId)) file.delete()
        }
        finish
    }

    }

    private fun saveRingtone(uri: String?, name: String, accountId: String = ringtoneAccountId ?: prefs.selectedAccount().id): Boolean {
        if (!idle()) return false
        val account = prefs.accounts().firstOrNull { it.id == accountId } ?: return false
        if ("ringtoneUri" in account.managedKeys) return false
        ringtonePreview.stop()
        val previous = account.options.ringtoneUri
        prefs.saveOptions(account.options.copy(ringtoneUri = uri, ringtoneName = name), accountId)
        activity.sip?.updateRingtone()
        if (previous != uri && previous?.startsWith("file:") == true && prefs.accounts().none { it.options.ringtoneUri == previous }) {
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

    private fun work(error: String, resultModal: Boolean = false, abandoned: () -> Unit = {}, action: () -> (() -> Unit)) {
        pendingWork++
        worker.execute {
            val outcome = runCatching(action)
            activity.runOnUiThread {
                pendingWork--
                if (!activity.isDestroyed) outcome.onSuccess { it() }.onFailure { if (resultModal) resultDialog("Operation failed", error) else activity.message(error) }
                else abandoned()
            }
        }
    }

    companion object {
        private const val RINGTONE = 40
        private const val AUDIO = 41
        private const val EXPORT = 42
        private const val IMPORT = 43
        private const val LOCAL_EXPORT = 44
        private const val EVENT_EXPORT = 45
    }
}
