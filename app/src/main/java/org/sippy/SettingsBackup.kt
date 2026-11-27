package org.sippy

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class BackupSection(val label: String) {
    ACCOUNTS("SIP accounts, media encryption and outgoing selection"), CODECS("Account audio codecs"), RINGTONES("Account ringtones (including custom audio)"),
    TRANSFERS("Account transfer modes"), DND("Account DND behaviour and state"), NETWORK("Device network and media settings"),
    SOUNDS("Device sounds and vibration"), APPLICATION("Application settings"), PHONEBOOK("Phonebook sync settings"), LDAP("LDAP settings"),
    PASSWORDS("Passwords (readable in the backup; managed passwords excluded)")
}
internal data class BackupReport(val applied: Int, val skipped: List<String>, val notes: List<String> = emptyList()) {
    fun text() = buildString {
        append("$applied settings applied.")
        if (skipped.isNotEmpty()) append("\n\nSkipped (${skipped.size}):\n" + skipped.distinct().joinToString("\n"))
        if (notes.isNotEmpty()) append("\n\n" + notes.distinct().joinToString("\n"))
    }
}
internal data class BackupExport(val root: JSONObject, val notes: List<String>)

/** Explicit settings allowlist: never serialize raw preferences, encrypted blobs, history or policy bundles. */
internal class SettingsBackup(private val context: Context, private val prefs: Preferences = Preferences(context)) {
    companion object {
        const val MAX_BYTES = 128 * 1024 * 1024
        private const val AUDIO_LIMIT = 20 * 1024 * 1024
        val groups: Map<BackupSection, Map<String, Any>> = linkedMapOf(
            BackupSection.NETWORK to linkedMapOf("localPort" to 0, "rtpPort" to 0, "dtmf" to "RTP", "echo" to true, "ipv6" to true),
            BackupSection.SOUNDS to linkedMapOf("dialpadTones" to true, "vibrateOnIncoming" to false),
            BackupSection.APPLICATION to linkedMapOf("appearance" to "SYSTEM", "backgroundCalling" to true, "startOnBoot" to true, "eventRetentionDays" to 3),
            BackupSection.PHONEBOOK to linkedMapOf("csvUrl" to "", "csvInterval" to 60),
            BackupSection.LDAP to linkedMapOf("ldapEnabled" to false, "ldapHost" to "", "ldapPort" to 636, "ldapTls" to true,
                "ldapBindDn" to "", "ldapBaseDn" to "", "ldapFilter" to "(objectClass=*)", "ldapNames" to "displayName,cn,givenName,sn",
                "ldapPhones" to "telephoneNumber,mobile,homePhone,ipPhone", "ldapClientCertificateAlias" to "")
        )
        private fun sectionKey(section: BackupSection) = section.name.lowercase(java.util.Locale.ROOT)
        fun available(root: JSONObject): Set<BackupSection> {
            require(root.optString("format") == "sippy-settings-backup" && root.get("version") == 2) { "Unsupported settings backup format." }
            val list = root.getJSONArray("sections")
            return (0 until list.length()).map { BackupSection.valueOf(list.getString(it)) }.toSet()
        }
        fun read(text: String): JSONObject {
            val root = JSONObject(text)
            if (root.optString("format") == "sippy-settings-backup") { available(root); return root }
            val old = SipSettingsJson.decode(text)
            val account = SipAccountFields.encode(old.profile)
            if (!root.getJSONObject("account").has("password")) account.remove("password")
            val sections = mutableSetOf(BackupSection.ACCOUNTS, BackupSection.CODECS, BackupSection.DND, BackupSection.NETWORK)
            if (account.has("password")) sections.add(BackupSection.PASSWORDS)
            val record = JSONObject().put("id", "legacy-import").put("name", old.name ?: "Imported account").put("managed", false)
                .put("account", account).put("codecs", AccountOptions.codecJson(old.codecs)).put("dnd", old.dnd.json())
            val network = JSONObject().put("localPort", old.profile.localPort).put("rtpPort", old.profile.rtpPort)
                .put("dtmf", old.profile.dtmf.name).put("echo", old.profile.echoCancellation).put("ipv6", old.profile.ipv6)
            return JSONObject().put("format", "sippy-settings-backup").put("version", 2).put("sections", JSONArray(sections.map { it.name }))
                .put("accounts", JSONArray().put(record)).put("selectedAccountId", "legacy-import").put("network", network).put("legacy", true)
        }
    }

    private val accountSections = setOf(BackupSection.ACCOUNTS, BackupSection.CODECS, BackupSection.RINGTONES, BackupSection.TRANSFERS, BackupSection.DND)

    fun prepareAudio(root: JSONObject, sections: Set<BackupSection>): Pair<JSONObject, List<File>> {
        val copy = JSONObject(root.toString())
        val files = mutableListOf<File>()
        try {
            if (BackupSection.RINGTONES in sections) {
                val records = copy.optJSONArray("accounts") ?: JSONArray()
                require(records.length() <= 40)
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    val target = prefs.accounts().firstOrNull { it.id == record.optString("id") }
                        ?: prefs.accounts().firstOrNull { it.name.equals(record.optString("name"), true) }
                    if (target?.managedKeys?.contains("ringtoneUri") == true || target == null && (record.optBoolean("managed") || BackupSection.ACCOUNTS !in sections)) continue
                    val tone = record.optJSONObject("ringtone") ?: continue
                    if (!tone.has("audio")) continue
                    val encoded = tone.getString("audio"); require(encoded.length <= 28 * 1024 * 1024)
                    val bytes = Base64.decode(encoded, Base64.DEFAULT); require(bytes.size in 1..AUDIO_LIMIT)
                    require(files.sumOf { it.length() } + bytes.size <= 80L * 1024 * 1024)
                    val extension = tone.getString("extension"); require(extension in setOf("wav", "mp3"))
                    val file = File.createTempFile("ringtone-", ".$extension", context.filesDir)
                    files.add(file); file.writeBytes(bytes)
                    val player = MediaPlayer()
                    try { player.setDataSource(file.absolutePath); player.prepare(); require(player.duration > 0) }
                    finally { player.release() }
                    tone.remove("audio"); tone.remove("extension"); tone.put("uri", Uri.fromFile(file).toString())
                }
            }
            return copy to files
        } catch (failure: Exception) { files.forEach { it.delete() }; throw failure }
    }

    fun export(sections: Set<BackupSection>): BackupExport {
        require(sections.any { it != BackupSection.PASSWORDS }) { "Select at least one settings category." }
        val notes = mutableListOf<String>()
        val root = JSONObject().put("format", "sippy-settings-backup").put("version", 2).put("sections", JSONArray(sections.map { it.name }))
        val accounts = JSONArray()
        var audioTotal = 0L
        if (sections.any { it in accountSections }) prefs.accounts().forEach { config ->
            val item = JSONObject().put("id", config.id).put("name", config.name).put("managed", config.managed)
            if (BackupSection.ACCOUNTS in sections) {
                val account = SipAccountFields.encode(config.profile)
                if (BackupSection.PASSWORDS !in sections || "password" in config.managedKeys) account.remove("password")
                if (BackupSection.PASSWORDS in sections && "password" in config.managedKeys) notes.add("${config.name}: MDM-managed SIP password excluded.")
                item.put("account", account)
            }
            if (BackupSection.CODECS in sections) item.put("codecs", AccountOptions.codecJson(config.options.codecs))
            if (BackupSection.TRANSFERS in sections) item.put("transferMode", config.options.transferMode.name)
            if (BackupSection.DND in sections) item.put("dnd", config.dnd.json()).put("dndEnabled", prefs.dndEnabled(config.id))
            if (BackupSection.RINGTONES in sections) {
                val tone = JSONObject().put("uri", config.options.ringtoneUri ?: JSONObject.NULL).put("name", config.options.ringtoneName)
                val uri = config.options.ringtoneUri?.let(Uri::parse)
                if (uri?.scheme == "file") {
                    try {
                        val file = File(requireNotNull(uri.path))
                        require(file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.name.startsWith("ringtone-"))
                        require(file.length() in 1..AUDIO_LIMIT.toLong())
                        audioTotal += file.length(); require(audioTotal <= 80L * 1024 * 1024)
                        tone.put("audio", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)).put("extension", if (file.extension.equals("mp3", true)) "mp3" else "wav")
                    } catch (_: Exception) {
                        tone.put("uri", JSONObject.NULL).put("name", "Default ringtone")
                        notes.add("${config.name}: custom ringtone could not be included; default ringtone exported.")
                    }
                }
                item.put("ringtone", tone)
            }
            accounts.put(item)
        }
        root.put("accounts", accounts)
        if (BackupSection.ACCOUNTS in sections) root.put("selectedAccountId", prefs.selectedAccount().id)
        groups.forEach { (section, fields) -> if (section in sections) {
            val values = JSONObject()
            fields.forEach { (key, default) -> values.put(key, when (default) {
                is Boolean -> prefs.data.getBoolean(key, default)
                is Int -> prefs.data.getInt(key, default)
                else -> prefs.data.getString(key, default as String)
            }) }
            if (section == BackupSection.LDAP && BackupSection.PASSWORDS in sections) {
                if (!prefs.isManaged("ldapPassword")) values.put("ldapPassword", prefs.ldap().password)
                else notes.add("MDM-managed LDAP password excluded.")
            }
            root.put(sectionKey(section), values)
        } }
        return BackupExport(root, notes)
    }

    fun restore(source: JSONObject, sections: Set<BackupSection>): BackupReport {
        val root = JSONObject(source.toString())
        // Early v1.2.0 backups stored encryption globally. Move it into each
        // included account before field-level MDM validation, without overriding newer values.
        val legacyEncryption = root.optJSONObject("network")?.opt("encryption")
        if (legacyEncryption != null) root.optJSONArray("accounts")?.let { records ->
            for (i in 0 until records.length()) records.getJSONObject(i).optJSONObject("account")?.let { account ->
                if (!account.has("encryption")) account.put("encryption", legacyEncryption)
            }
        }
        val available = available(root)
        require(sections.all { it in available } && sections.any { it != BackupSection.PASSWORDS }) { "Select at least one available settings category." }
        val original = prefs.localSnapshot()
        val previousSelection = prefs.selectedAccount().id
        val createdFiles = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (legacyEncryption != null && BackupSection.NETWORK in sections && BackupSection.ACCOUNTS !in sections)
            notes.add("Legacy shared media encryption was skipped. Select SIP accounts to restore it per account.")
        var applied = 0
        var stage = "backup structure"
        try {
            val input = if (sections.any { it in accountSections }) root.optJSONArray("accounts") ?: JSONArray() else JSONArray()
            require(input.length() <= 40) { "Too many accounts in this backup." }
            val seen = mutableSetOf<String>()
            val mappings = mutableMapOf<String, String>()
            for (i in 0 until input.length()) {
                val record = input.getJSONObject(i)
                val name = SipAccountFields.name(record.getString("name"))
                val sourceId = record.getString("id").also { require(it.length <= 100 && seen.add(it)) { "Duplicate or invalid account ID." } }
                stage = "account '$name'"
                var target = prefs.accounts().firstOrNull { it.id == sourceId } ?: prefs.accounts().firstOrNull { it.name.equals(name, true) }
                if (record.optBoolean("managed", false) && target?.managed != true) {
                    skipped.add("$name: managed account is not provisioned on this device."); continue
                }
                if (target == null) {
                    if (record.optBoolean("managed", false)) { skipped.add("$name: managed account is not provisioned on this device."); continue }
                    if (BackupSection.ACCOUNTS !in sections) { skipped.add("$name: account does not exist; select SIP accounts to create it."); continue }
                    prefs.addAccount(name); target = prefs.selectedAccount(); applied++
                }
                prefs.selectAccount(target.id)
                if (BackupSection.ACCOUNTS in sections && target.name != name) {
                    if (target.managed) skipped.add("$name: account name is managed by your organization.")
                    else { prefs.renameAccount(name); applied++ }
                }
                mappings[sourceId] = target.id
                fun allowed(key: String): Boolean {
                    if (!prefs.isManaged(key)) return true
                    skipped.add("$name: $key is managed by your organization."); return false
                }
                if (BackupSection.ACCOUNTS in sections && record.has("account")) {
                    stage = "SIP settings for '$name'"
                    val values = record.getJSONObject("account")
                    val merged = SipAccountFields.encode(prefs.profile())
                    SipAccountFields.keys.filter { values.has(it) }.forEach { key ->
                        if (key == "password" && BackupSection.PASSWORDS !in sections) return@forEach
                        if (allowed(key)) { merged.put(key, values.get(key)); applied++ }
                    }
                    prefs.save(SipAccountFields.decode(merged, prefs.profile()))
                }
                stage = "account options for '$name'"
                var options = prefs.selectedAccount().options
                if (BackupSection.CODECS in sections && record.has("codecs") && allowed("codecs")) {
                    options = options.copy(codecs = AccountOptions.decodeCodecs(record.getJSONArray("codecs"))); applied++
                }
                if (BackupSection.TRANSFERS in sections && record.has("transferMode") && allowed("transferMode")) {
                    options = options.copy(transferMode = TransferMode.valueOf(record.getString("transferMode"))); applied++
                }
                if (BackupSection.RINGTONES in sections && record.has("ringtone") && allowed("ringtoneUri")) {
                    val tone = record.getJSONObject("ringtone")
                    var uri: String? = if (tone.isNull("uri")) null else tone.getString("uri")
                    var label = tone.getString("name")
                    require(label.length <= 256 && label.none(Char::isISOControl)) { "Invalid ringtone name." }
                    if (tone.has("audio")) {
                        val encoded = tone.getString("audio"); require(encoded.length <= 28 * 1024 * 1024) { "Ringtone is too large." }
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        require(bytes.size in 1..AUDIO_LIMIT) { "Ringtone is too large." }
                        require(createdFiles.sumOf { it.length() } + bytes.size <= 80L * 1024 * 1024) { "Too much ringtone audio." }
                        val extension = tone.getString("extension"); require(extension in setOf("wav", "mp3")) { "Unsupported ringtone format." }
                        val file = File.createTempFile("ringtone-", ".$extension", context.filesDir)
                        createdFiles.add(file); file.writeBytes(bytes)
                        val player = MediaPlayer()
                        try { player.setDataSource(file.absolutePath); player.prepare(); require(player.duration > 0) { "Invalid ringtone audio." } }
                        finally { player.release() }
                        uri = Uri.fromFile(file).toString()
                    } else if (uri != null) {
                        val parsed = Uri.parse(uri)
                        require(parsed.scheme in setOf("content", "android.resource", "file")) { "Invalid ringtone URI." }
                        val usable = runCatching {
                            if (parsed.scheme == "file") {
                                val f = File(parsed.path.orEmpty()); require(f.canonicalFile.parentFile == context.filesDir.canonicalFile && f.name.startsWith("ringtone-"))
                            }
                            context.contentResolver.openInputStream(parsed)?.use { it.read() } ?: error("Unavailable ringtone")
                        }.isSuccess
                        if (!usable) { uri = null; label = "Default ringtone"; skipped.add("$name: ringtone unavailable on this device; using default.") }
                    }
                    options = options.copy(ringtoneUri = uri, ringtoneName = if (uri == null) "Default ringtone" else label); applied++
                }
                prefs.saveOptions(options)
                if (BackupSection.DND in sections && record.has("dnd")) {
                    val merged = prefs.selectedAccount().dnd.json(); val values = record.getJSONObject("dnd")
                    DndSettings.keys.forEach { key ->
                        val field = key.removePrefix("dnd").replaceFirstChar { it.lowercase() }
                        if (values.has(field) && allowed(key)) { merged.put(field, values.get(field)); applied++ }
                    }
                    prefs.saveDnd(DndSettings.decode(merged))
                    if (record.has("dndEnabled")) {
                        if (allowed("doNotDisturb")) {
                            val enabled = record.get("dndEnabled") as? Boolean ?: error("Invalid DND state.")
                            if (prefs.setDndEnabled(target.id, enabled)) applied++ else skipped.add("$name: DND is disabled for this account.")
                        }
                    }
                }
            }
            groups.forEach { (section, fields) -> if (section in sections && root.has(sectionKey(section))) {
                stage = section.label
                val values = root.getJSONObject(sectionKey(section))
                val allowed = JSONObject()
                fields.forEach { (key, default) -> if (values.has(key)) {
                    if (prefs.isManaged(key)) skipped.add("${section.label}: $key is managed by your organization.")
                    else {
                        val value = values.get(key)
                        require(when(default) { is Boolean -> value is Boolean; is Int -> value is Int; else -> value is String && value.length <= 16384 }) { "Invalid ${section.label} field type." }
                        allowed.put(key, value)
                    }
                } }
                fun str(key: String, fallback: String = "") = allowed.optString(key, prefs.data.getString(key, fallback))
                fun num(key: String, fallback: Int) = if (allowed.has(key)) allowed.getInt(key) else prefs.data.getInt(key, fallback)
                if (section == BackupSection.NETWORK) {
                    require(num("localPort", 0) in 0..65535 && num("rtpPort", 0) in 0..65534) { "Invalid local SIP/RTP port." }
                    Dtmf.valueOf(str("dtmf", "RTP"))
                }
                if (section == BackupSection.APPLICATION) {
                    Appearance.valueOf(str("appearance", "SYSTEM")); require(num("eventRetentionDays", 3) in 1..30) { "Invalid event log retention." }
                }
                if (section == BackupSection.PHONEBOOK) {
                    if (str("csvUrl").isNotBlank()) checkedUrl(str("csvUrl"))
                    require(num("csvInterval", 60) in 15..10080) { "Invalid phonebook sync interval." }
                }
                val editor = prefs.data.edit()
                if (section == BackupSection.PHONEBOOK && allowed.length() > 0) {
                    if (allowed.has("csvUrl") && allowed.getString("csvUrl") != prefs.data.getString("csvUrl", "")) {
                        editor.remove("entries_SYNC").remove("lastSync").remove("syncError")
                    }
                    editor.putInt("csvRevision", prefs.data.getInt("csvRevision", 0) + 1)
                }
                allowed.keys().forEach { key -> when(val value = allowed.get(key)) {
                    is Boolean -> editor.putBoolean(key,value); is Int -> editor.putInt(key,value); is String -> editor.putString(key,value)
                }; applied++ }
                check(editor.commit()) { "Could not save settings." }
                if (section == BackupSection.LDAP) {
                    var config = prefs.ldap()
                    if (BackupSection.PASSWORDS in sections && values.has("ldapPassword")) {
                        if (prefs.isManaged("ldapPassword")) skipped.add("LDAP password is managed by your organization.")
                        else { config = config.copy(password = values.get("ldapPassword") as? String ?: error("Invalid LDAP password.")); applied++ }
                    }
                    if (config.host.isNotEmpty()) config.copy(bindDn = "", password = "").validate()
                    // saveLdap requires credentials; persist a password independently for partially provisioned directories.
                    prefs.saveLdapPassword(config.password)
                    if (allowed.has("ldapClientCertificateAlias") && config.clientCertificateAlias.isNotEmpty()) notes.add("The LDAP certificate alias was restored. Install its certificate/private key and grant access separately on this phone.")
                }
            } }
            val selected = if (BackupSection.ACCOUNTS in sections) mappings[root.optString("selectedAccountId")] else null
            if (selected != null) prefs.selectAccount(selected) else if (prefs.accounts().any { it.id == previousSelection }) prefs.selectAccount(previousSelection)
            return BackupReport(applied, skipped, notes)
        } catch (failure: Exception) {
            prefs.restoreSnapshot(original)
            createdFiles.forEach { it.delete() }
            throw IllegalArgumentException("Import failed while restoring $stage. No settings were changed. " +
                if (failure is org.json.JSONException) "The backup has a missing field or an incorrect field type in this section." else "A selected value is invalid, conflicts with an existing account, or could not be saved. Check this section in the backup.", failure)
        }
    }
}
