package org.sippy

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Device-side checks use Android's real JSON parser without additional dependencies. */
class SettingsChecks : Instrumentation() {
    private var checks: String? = null
    override fun onCreate(arguments: Bundle?) { checks = arguments?.getString("checks"); super.onCreate(arguments); start() }
    private fun status(message: String) = sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    override fun onStart() {
        val result = Bundle()
        try {
            if (checks in setOf("ldap", "calls", "transfer", "mdm", "accounts", "dnd", "backup", "account_codecs", "troubleshooting", "encryption")) {
                when (checks) {
                    "ldap" -> LdapChecks(this).run()
                    "transfer" -> TransferModeChecks(this).run()
                    "mdm" -> ManagedConfigurationChecks(this).run()
                    "accounts" -> AccountChecks(this).run()
                    "dnd" -> DndChecks(this).run()
                    "backup" -> SettingsBackupChecks(this).run()
                    "account_codecs" -> AccountCodecChecks(this).run()
                    "troubleshooting" -> TroubleshootingChecks(this).run()
                    "encryption" -> AccountEncryptionChecks(this).run()
                    else -> IncomingCallChecks(this).run()
                }
                result.putString("stream", "Passed: $checks checks.\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            val p = SipProfile(username = "201", domain = "pbx.example.com", password = "test-\"\\\n-secret",
                authUser = "auth201", displayName = "Desk", server = "registrar.example.com:5061",
                proxy = "proxy.example.com:5061", realm = "example", transport = Transport.TLS,
                register = false, expiry = 3600, localPort = 5062, rtpPort = 12000,
                encryption = Encryption.SRTP, dtmf = Dtmf.INFO, stun = "stun.example.com:3478",
                ice = true, echoCancellation = false, ipv6 = false)
            val codecs = Preferences.defaultCodecs.reversed()
            val json = SipSettingsJson.encode(p, codecs, true)
            check(SipSettingsJson.decode(json) == SipSettingsBackup(p, codecs))
            val withoutPassword = SipSettingsJson.encode(p, codecs, false)
            check(!JSONObject(withoutPassword).getJSONObject("account").has("password"))
            check(SipSettingsJson.decode(withoutPassword).profile == p.copy(password = ""))
            val empty = SipProfile()
            check(SipSettingsJson.decode(SipSettingsJson.encode(empty, codecs, false)).profile == empty)
            fun rejects(change: (JSONObject) -> Unit) {
                val altered = JSONObject(json).also(change)
                check(runCatching { SipSettingsJson.decode(altered.toString()) }.isFailure)
            }
            rejects { it.put("version", 2) }
            rejects { it.getJSONObject("account").put("localPort", 65536) }
            rejects { it.getJSONObject("account").put("register", "false") }
            rejects { it.getJSONObject("account").put("expiry", 600.5) }
            rejects { it.getJSONObject("account").put("transport", "BOGUS") }
            rejects { it.getJSONObject("account").put("domain", "https://pbx.example.com") }
            rejects { it.getJSONArray("codecs").getJSONObject(1).put("id", codecs[0].id) }
            rejects { root ->
                val items = root.getJSONArray("codecs")
                for (i in 0 until items.length()) items.getJSONObject(i).put("enabled", false)
            }
            rejects { it.remove("account") }
            check(runCatching { SipSettingsJson.decode("not json") }.isFailure)
            LdapChecks(this).run()
            status("LDAP checks passed.")
            HistoryCsvChecks(this).run()
            ProximityChecks(this).run()
            AppearanceChecks(this).run()
            status("Appearance checks passed; checking calls and DTMF feedback.")
            IncomingCallChecks(this).run()
            checkAudioImport()
            result.putString("stream", "Passed: LDAP TLS, authentication, searching, escaping and encrypted settings; durable history clearing and database upgrade, local-only CSV round trip, closed-app calls, background availability, notification actions, themes, JSON, audio import, and preview playback.\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAILED: ${failure.javaClass.simpleName}: ${failure.message}\n${failure.stackTrace.take(8).joinToString("\n")}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkAudioImport() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefs = activity.prefs
        val oldOptions = prefs.selectedAccount().options
        val oldUri = oldOptions.ringtoneUri
        val oldName = oldOptions.ringtoneName
        val oldFile = oldUri?.takeIf { it.startsWith("file:") }?.let { File(Uri.parse(it).path!!) }
        val oldAudio = oldFile?.takeIf { it.exists() }?.readBytes()
        try {
            checkPreview(activity, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            for (extension in listOf("wav", "mp3")) {
                val fixture = File(activity.cacheDir, "sippy-tone.$extension")
                context.assets.open(fixture.name).use { input -> fixture.outputStream().use { input.copyTo(it) } }
                runOnMainSync {
                    activity.settingsFiles.result(41, Activity.RESULT_OK, Intent().setData(Uri.fromFile(fixture)))
                }
                val deadline = System.currentTimeMillis() + 10000
                while (prefs.selectedAccount().options.ringtoneName != fixture.name && System.currentTimeMillis() < deadline) Thread.sleep(50)
                check(prefs.selectedAccount().options.ringtoneName == fixture.name) { "$extension import failed" }
                val copied = File(Uri.parse(prefs.selectedAccount().options.ringtoneUri).path!!)
                check(copied.parentFile == activity.filesDir && copied.readBytes().contentEquals(fixture.readBytes()))
                checkPreview(activity, Uri.fromFile(copied))
                fixture.delete()
            }
            val validUri = prefs.selectedAccount().options.ringtoneUri
            val invalid = File(activity.cacheDir, "invalid.mp3").apply { writeText("not audio") }
            runOnMainSync { activity.settingsFiles.result(41, Activity.RESULT_OK, Intent().setData(Uri.fromFile(invalid))) }
            Thread.sleep(1000)
            waitForIdleSync()
            check(prefs.selectedAccount().options.ringtoneUri == validUri) { "Invalid audio replaced the ringtone" }
            invalid.delete()
        } finally {
            val latest = prefs.selectedAccount().options.ringtoneUri
            if (latest != oldUri && latest?.startsWith("file:") == true) File(Uri.parse(latest).path!!).delete()
            if (oldFile != null && oldAudio != null) oldFile.writeBytes(oldAudio)
            prefs.saveOptions(oldOptions)
            runOnMainSync { activity.sip?.updateRingtone(); activity.finish() }
        }
    }

    private fun checkPreview(activity: MainActivity, uri: Uri) {
        var errors = 0
        val preview = RingtonePreview(activity) { errors++ }
        val field = RingtonePreview::class.java.getDeclaredField("player").apply { isAccessible = true }
        fun actuallyPlaying(): Boolean {
            var playing = false
            runOnMainSync { playing = (field.get(preview) as? MediaPlayer)?.isPlaying == true }
            return playing
        }
        fun awaitPlaying() {
            val deadline = System.currentTimeMillis() + 5000
            while (!actuallyPlaying() && System.currentTimeMillis() < deadline) Thread.sleep(25)
            check(actuallyPlaying()) { "Preview did not start for $uri" }
        }
        try {
            runOnMainSync { preview.toggle(uri); preview.toggle(uri) }
            Thread.sleep(300)
            check(!actuallyPlaying()) { "Preview started after pause during preparation" }
            runOnMainSync { check(!preview.isPlaying); preview.toggle(uri) }
            awaitPlaying()
            runOnMainSync { preview.toggle(uri); check(!preview.isPlaying) }
            check(!actuallyPlaying()) { "Preview did not pause" }
            runOnMainSync { preview.toggle(uri) }
            awaitPlaying()
            runOnMainSync { preview.stop(); check(!preview.isPlaying) }
            check(!actuallyPlaying()) { "Preview did not stop" }
            check(errors == 0) { "Unexpected preview failure" }
            runOnMainSync { preview.toggle(Uri.fromFile(File(activity.cacheDir, "missing-ringtone.mp3"))) }
            val deadline = System.currentTimeMillis() + 5000
            while (errors == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
            runOnMainSync { check(errors == 1 && !preview.isPlaying); preview.toggle(uri) }
            awaitPlaying()
        } finally {
            runOnMainSync { preview.stop() }
        }
    }
}
