package org.sippy

import android.app.Instrumentation
import android.os.Bundle
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Real encrypted-preference migration, complete backups, custom audio and field-level MDM enforcement. */
internal class SettingsBackupChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    fun run() {
        val context = instrumentation.targetContext
        check(!ManagedConfiguration.present)
        val prefs = Preferences(context)
        val snapshot = prefs.localSnapshot()
        val all = BackupSection.entries.toSet()
        val backup = SettingsBackup(context, prefs)
        val tone = File.createTempFile("ringtone-", ".wav", context.filesDir)
        val originalFiles = context.filesDir.listFiles().orEmpty().toSet()
        try {
            instrumentation.context.assets.open("sippy-tone.wav").use { input -> tone.outputStream().use { input.copyTo(it) } }
            main {
                prefs.restoreSnapshot(emptyMap<String, Any>())
                prefs.save(SipProfile(username = "201", authUser = "auth201", domain = "127.0.0.1", register = false, password = "UPGRADE_SIP_SECRET"))
                // Exactly the v1.1.8 representation: flat SIP fields and encrypted password,
                // global codec/ringtone/transfer settings, no named-account records.
                prefs.data.edit().remove("sipAccountsLocal").remove("sipAccountOverrides")
                    .putString("codecs", AccountOptions.codecJson(AccountOptions.codecOrder("pcmu,pcma")).toString())
                    .putString("ringtoneUri", Uri.fromFile(tone).toString()).putString("ringtoneName", "Upgrade ringtone")
                    .putString("transferMode", "ATTENDED_ONLY").putString("appearance", "DARK")
                    .putBoolean("dialpadTones", false).putBoolean("vibrateOnIncoming", true).putInt("eventRetentionDays", 7).commit()
                val migrated = Preferences(context).selectedAccount()
                check(migrated.id == "default" && migrated.name == "Default account")
                check(migrated.profile.password == "UPGRADE_SIP_SECRET" && migrated.profile.authUser == "auth201")
                check(migrated.options.codecs.take(2).map { it.id } == listOf("pcmu","pcma"))
                check(migrated.options.ringtoneUri == Uri.fromFile(tone).toString() && migrated.options.transferMode == TransferMode.ATTENDED_ONLY)
                check(prefs.appearance == Appearance.DARK && !prefs.dialpadTones && prefs.vibrateOnIncoming)
                // Migration is one-time: changing a leftover legacy field must not change account options.
                prefs.data.edit().putString("transferMode", "BOTH").putString("codecs", "[]").commit()
                check(Preferences(context).transferMode == TransferMode.ATTENDED_ONLY)
                prefs.renameAccount("Office")
                prefs.addAccount("Home", SipProfile(username="202",domain="127.0.0.2",register=false,password="HOME_SIP_SECRET"),
                    options = AccountOptions(codecs=AccountOptions.codecOrder("pcma"),transferMode=TransferMode.DIRECT_ONLY))
                check(prefs.codecs().first().id == "pcma" && prefs.transferMode == TransferMode.DIRECT_ONLY)
                prefs.saveLdap(LdapConfig(host="ldap.example.com",bindDn="ldap-user",password="LDAP_LOCAL_SECRET"))
                prefs.data.edit().putString("csvUrl","https://example.com/contacts.csv").putInt("csvInterval",90).commit()
            }
            val exported = backup.export(all).root
            val noPasswords = backup.export(all - BackupSection.PASSWORDS).root.toString()
            check(!noPasswords.contains("UPGRADE_SIP_SECRET") && !noPasswords.contains("LDAP_LOCAL_SECRET"))
            check(exported.toString().contains("UPGRADE_SIP_SECRET") && exported.toString().contains("LDAP_LOCAL_SECRET"))
            main {
                prefs.restoreSnapshot(emptyMap<String, Any>())
                val report = backup.restore(exported,all)
                check(report.applied > 20 && report.skipped.isEmpty()) { report.text() }
                check(prefs.accounts().map { it.name }.toSet() == setOf("Office","Home"))
                check(prefs.selectedAccount().name == "Home" && prefs.profile().password == "HOME_SIP_SECRET")
                check(prefs.transferMode == TransferMode.DIRECT_ONLY && prefs.codecs().first().id == "pcma")
                prefs.selectAccount(prefs.accounts().first { it.name == "Office" }.id)
                check(prefs.transferMode == TransferMode.ATTENDED_ONLY && prefs.profile().password == "UPGRADE_SIP_SECRET")
                val restoredTone = File(Uri.parse(prefs.selectedAccount().options.ringtoneUri).path!!)
                check(restoredTone != tone && restoredTone.readBytes().contentEquals(tone.readBytes()))
                check(prefs.appearance == Appearance.DARK && prefs.ldap().password == "LDAP_LOCAL_SECRET")
                // A codecs-only restore must not change account credentials, ringtones or app settings.
                prefs.appearance = Appearance.LIGHT
                prefs.transferMode = TransferMode.BOTH
                backup.restore(exported,setOf(BackupSection.CODECS))
                check(prefs.appearance == Appearance.LIGHT && prefs.transferMode == TransferMode.BOTH && prefs.profile().password == "UPGRADE_SIP_SECRET")
                val beforeFailure = prefs.localSnapshot()
                val broken = JSONObject(exported.toString()).apply { getJSONObject("application").put("eventRetentionDays",0) }
                check(runCatching { backup.restore(broken,all) }.isFailure)
                check(prefs.localSnapshot() == beforeFailure) { "Failed import did not roll back settings" }
            }
            val managed = Bundle().apply {
                putString("id","managed-office"); putString("name","Managed Office"); putString("accountUsername","300")
                putString("accountDomain","127.0.0.3"); putString("accountServer","127.0.0.3:5090"); putBoolean("accountRegister",false)
                putString("accountPassword","ADMIN_SIP_SECRET"); putString("accountCodecOrder","pcmu")
                putString("accountRingtoneUri",""); putString("accountTransferMode","DIRECT_ONLY")
            }
            main {
                ManagedConfiguration.apply(context,Bundle().apply {
                    putBoolean("managedConfigurationEnabled",true); putString("appearance","LIGHT")
                    putParcelableArray("sipAccounts",arrayOf(managed))
                })
                prefs.selectAccount("managed:managed-office")
                check(prefs.isManaged("codecs") && prefs.isManaged("ringtoneUri") && prefs.isManaged("transferMode"))
            }
            val managedExport = backup.export(all)
            check(!managedExport.root.toString().contains("ADMIN_SIP_SECRET")) { "Managed SIP password was exported" }
            check(managedExport.notes.any { it.contains("MDM-managed SIP password") })
            val records = managedExport.root.getJSONArray("accounts")
            val record = (0 until records.length()).map(records::getJSONObject).first { it.getString("id")=="managed:managed-office" }
            record.getJSONObject("account").put("username","attack").put("password","IMPORTED_PASSWORD").put("domain","blocked.invalid")
            record.put("transferMode","BOTH").put("codecs",AccountOptions.codecJson(AccountOptions.codecOrder("pcma")))
            record.getJSONObject("ringtone").put("uri","https://invalid.example/ignored.wav")
            managedExport.root.getJSONObject("application").put("appearance","DARK")
            main {
                val report=backup.restore(managedExport.root,all)
                prefs.selectAccount("managed:managed-office")
                check(report.skipped.any { it.contains("password") } && report.skipped.any { it.contains("codecs") }) { report.text() }
                check(prefs.profile().username=="300" && prefs.profile().password=="ADMIN_SIP_SECRET" && prefs.profile().domain=="127.0.0.3")
                check(prefs.codecs().first().id=="pcmu" && prefs.transferMode==TransferMode.DIRECT_ONLY && prefs.selectedAccount().options.ringtoneUri==null)
                check(prefs.appearance==Appearance.LIGHT)
                check(prefs.localSnapshot().values.none { it.toString().contains("ADMIN_SIP_SECRET") })
                ManagedConfiguration.apply(context,Bundle.EMPTY)
                prefs.addAccount("Managed Office")
                val unprovisioned = backup.restore(managedExport.root,setOf(BackupSection.ACCOUNTS))
                check(unprovisioned.skipped.any { it.contains("not provisioned") })
                check(prefs.accounts().first { it.name=="Managed Office" }.profile.username.isEmpty())
            }
            val old = SipSettingsJson.encode(SipProfile(username="900",domain="127.0.0.1",register=false),Preferences.defaultCodecs,false,"Old account")
            val converted=SettingsBackup.read(old)
            check(SettingsBackup.available(converted).contains(BackupSection.ACCOUNTS) && converted.optBoolean("legacy"))
            main { backup.restore(converted,SettingsBackup.available(converted)); check(prefs.accounts().any { it.name=="Old account" && it.profile.username=="900" }) }
        } finally {
            main { ManagedConfiguration.apply(context,Bundle.EMPTY); prefs.restoreSnapshot(snapshot) }
            tone.delete()
            context.filesDir.listFiles().orEmpty().filter { it !in originalFiles && it.name.startsWith("ringtone-") }.forEach { it.delete() }
        }
    }
}
