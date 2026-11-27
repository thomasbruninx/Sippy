package org.sippy

import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.RestrictionsManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.linphone.core.Core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/** Uses a temporary, test-only Android device owner to exercise the real OS restrictions channel. */
internal class ManagedConfigurationChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(40) }
        error(message)
    }
    private fun publish(case: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n org.sippy.test/org.sippy.ManagedTestAdminActivity --es case $case"
        )).bufferedReader().use { reader ->
            while (true) { val line = reader.readLine() ?: break; if (line == "Complete") break }
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    fun run() {
        val context = instrumentation.targetContext
        check(context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp("org.sippy.test")) { "Set the test APK as temporary emulator device owner first" }
        await("Cold startup did not load OS restrictions") { main { ManagedConfiguration.present && Preferences(context).profile().username == "managed200" } }
        val schema = context.getSystemService(RestrictionsManager::class.java).getManifestRestrictions(context.packageName)
        check(schema.any { it.key == "managedConfigurationEnabled" } && schema.any { it.key == "sipAccounts" } && schema.none { it.key in SipAccountFields.keys }) { "MDM schema was not discoverable from the APK" }
        check(schema.first { it.key == "sipAccounts" }.restrictions.first().restrictions.first { it.key == "accountTransferMode" }.choiceValues.toList() == listOf("DIRECT_ONLY", "ATTENDED_ONLY", "BOTH"))
        check(schema.any { it.key == "csvContent" })
        check(Directory(context).stored(Source.CSV).map { it.number } == listOf("401", "402")) { "OS-managed CSV was not applied at startup" }
        val raw = Preferences(context, useManaged = false)
        val original = raw.data.all
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main { raw.save(SipProfile(username = "local200", domain = "127.0.0.1", password = "LOCAL_FIXTURE_SECRET", register = false)) }
            await("SIP service did not bind") { main { activity.sip != null && activity.sipState.ready } }
            val service = main { activity.sip!! }
            val prefs = activity.prefs
            check(prefs.profile().username == "managed200" && prefs.profile().password == "MDM_FIXTURE_SECRET")
            check(prefs.transferMode == TransferMode.ATTENDED_ONLY && prefs.codecs().take(2).map { it.id } == listOf("pcmu", "opus"))
            main {
                activity.settingsTab = 0; activity.openLdapSettings()
                val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                check(fields.first { it.contentDescription?.startsWith("Username / extension") == true }.isEnabled.not())
                check(fields.first { it.contentDescription?.startsWith("SIP domain") == true }.isEnabled.not())
                check(fields.first { it.contentDescription?.startsWith("Password") == true }.isEnabled.not())
                prefs.save(prefs.profile().copy(username = "attempted-override", displayName = "User editable name"))
            }
            check(prefs.profile().username == "managed200" && prefs.profile().displayName == "User editable name")
            check(raw.profile().username == "local200" && raw.profile().password == "LOCAL_FIXTURE_SECRET")
            check(raw.data.all.values.none { it.toString().contains("MDM_FIXTURE_SECRET") }) { "Managed password was copied into local preferences" }
            main {
                activity.settingsTab = 0; activity.renderPage()
                val display = views(activity.window.decorView).filterIsInstance<EditText>().first { it.contentDescription == "Caller display name" }
                check(display.isEnabled) { "Unmanaged field was locked" }
            }
            val exported = java.io.File(context.cacheDir, "managed-export-${UUID.randomUUID()}.json")
            try {
                main {
                    activity.settingsFiles.includePassword = true
                    activity.settingsFiles.result(42, android.app.Activity.RESULT_OK, Intent().setData(android.net.Uri.fromFile(exported)))
                }
                await("Managed settings export did not complete") { exported.exists() && exported.length() > 0 }
                val json = org.json.JSONObject(exported.readText())
                val records = json.getJSONArray("accounts")
                val managedRecord = (0 until records.length()).map(records::getJSONObject).first { it.getString("id") == prefs.selectedAccount().id }
                check(!managedRecord.getJSONObject("account").has("password") && !exported.readText().contains("MDM_FIXTURE_SECRET")) { "Managed password escaped into a SIP export" }
            } finally { exported.delete() }
            val coreField = SipService::class.java.getDeclaredField("core").apply { isAccessible = true }
            val core = main { coreField.get(service) as Core }
            val port = main { core.transportsUsed.udpPort }
            val loopback = InetAddress.getByName("127.0.0.1")
            DatagramSocket(0, loopback).use { socket ->
                val id = UUID.randomUUID().toString()
                val sdp = "v=0\r\no=mdm-check 1 1 IN IP4 127.0.0.1\r\ns=MDM check\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 19002 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n"
                val invite = listOf("INVITE sip:managed200@127.0.0.1:$port SIP/2.0", "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id;rport",
                    "Max-Forwards: 70", "From: <sip:mdm-check@127.0.0.1>;tag=$id", "To: <sip:managed200@127.0.0.1>", "Call-ID: $id", "CSeq: 1 INVITE",
                    "Contact: <sip:mdm-check@127.0.0.1:${socket.localPort}>", "Content-Type: application/sdp", "Content-Length: ${sdp.toByteArray().size}", "", sdp).joinToString("\r\n").toByteArray()
                socket.send(DatagramPacket(invite, invite.size, loopback, port))
                await("Managed account did not receive local test call") { main { service.state.lines.any { it.phase == Phase.INCOMING } } }
                val callId = main { service.state.lines.first().id }
                main { service.answer(callId) }
                socket.soTimeout = 10000
                var accepted: String
                do {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    socket.receive(packet)
                    accepted = String(packet.data, 0, packet.length)
                } while (!accepted.startsWith("SIP/2.0 200 "))
                val to = accepted.lineSequence().first { it.startsWith("To:", true) }.trim()
                val ack = listOf("ACK sip:managed200@127.0.0.1:$port SIP/2.0", "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id-ack;rport",
                    "Max-Forwards: 70", "From: <sip:mdm-check@127.0.0.1>;tag=$id", to, "Call-ID: $id", "CSeq: 1 ACK", "Content-Length: 0", "", "").joinToString("\r\n").toByteArray()
                socket.send(DatagramPacket(ack, ack.size, loopback, port))
                await("Managed test call did not connect") { main { service.state.lines.any { it.id == callId && it.phase == Phase.ACTIVE } } }
                val pointer = main { core.nativePointer }
                publish("changed")
                await("Running app did not receive restrictions broadcast") { main { prefs.profile().username == "managed201" } }
                main {
                    check(service.managedUpdatePending) { "Connection update was not deferred" }
                    check((coreField.get(service) as Core).nativePointer == pointer && service.state.lines.any { it.id == callId && it.phase == Phase.ACTIVE }) { "MDM update interrupted the call" }
                    service.end(callId)
                }
                await("Deferred configuration was not applied after the call") { main {
                    !service.managedUpdatePending && (coreField.get(service) as? Core)?.defaultAccount?.params?.identityAddress?.username == "managed201"
                } }
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) main {
                check(service.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) { "Managed reconnect retained microphone service access after the call" }
            }
            publish("invalid")
            await("Invalid policy was not reported") { main { ManagedConfiguration.error != null } }
            check(prefs.profile().username == "managed201" && !ManagedConfiguration.status.contains("DO_NOT_LOG_INVALID_FIXTURE"))
            publish("invalid_type")
            await("Wrong-typed setting was not rejected") { main { ManagedConfiguration.error?.contains("expiry", ignoreCase = true) == true } }
            check(prefs.profile().username == "managed201")
            publish("pending")
            await("Pending restrictions were not reported") { main { ManagedConfiguration.status.startsWith("Waiting for") } }
            check(prefs.profile().username == "managed201")
            publish("changed")
            await("Corrected policy was not applied") { main { ManagedConfiguration.error == null } }
            main { activity.finish() }
            await("Test activity did not close") { main { activity.isDestroyed } }
            publish("full")
            await("Closed activity stopped managed configuration updates") { main { prefs.profile().username == "managed200" } }
            publish("accounts")
            await("OS bundle-array policy was not loaded") { main { prefs.accounts().count { it.managed } == 2 } }
            main {
                service.selectAccount("managed:office")
                check(prefs.profile().username == "mdm300" && prefs.isManaged("password"))
                check(prefs.isManaged("dndShow") && prefs.isManaged("dndReasonHeader"))
                prefs.saveDnd(DndSettings(show = false, reasonHeader = false, serverSync = DndServerSync.FAC))
                check(prefs.selectedAccount().dnd.show && prefs.selectedAccount().dnd.reasonHeader && prefs.selectedAccount().dnd.serverSync == DndServerSync.DISABLED)
                service.setDoNotDisturb(true)
                check("managed:office" in service.state.dndAccounts)
                service.selectAccount("managed:support")
                check(prefs.profile().username == "mdm301" && !prefs.selectedAccount().dnd.show)
                check(prefs.data.all.values.none { it.toString().contains("MDM_MULTI") })
            }
            publish("accounts_invalid")
            await("Invalid nested DND policy was not rejected") { main { ManagedConfiguration.error != null } }
            check(prefs.accounts().count { it.managed } == 2)
            publish("accounts")
            await("Corrected array policy did not recover") { main { ManagedConfiguration.error == null } }
            publish("local_credentials")
            await("Per-account templates were not applied") { main { prefs.accounts().any { it.id == "managed:template-office" } } }
            main {
                service.selectAccount("managed:template-office")
                check(!prefs.profile().configured && prefs.profile().password.isEmpty())
                check(prefs.isManaged("domain") && prefs.isManaged("server") && prefs.isManaged("transport"))
                check(listOf("username", "authUser", "password").none(prefs::isManaged))
                activity.settingsTab = 0
            }
            // Reopen the UI after the closed-activity test to verify actual editability.
            val editor = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            main {
                editor.settingsTab = 0; editor.openLdapSettings()
                val fields = views(editor.window.decorView).filterIsInstance<EditText>()
                check(fields.first { it.contentDescription == "Username / extension" }.isEnabled)
                check(fields.first { it.contentDescription == "Password" }.isEnabled)
                check(fields.first { it.contentDescription == "Authentication ID" }.isEnabled)
                check(fields.first { it.contentDescription?.startsWith("Registrar override") == true }.isEnabled.not())
                check(fields.first { it.contentDescription?.startsWith("SIP domain") == true }.isEnabled.not())
                prefs.save(prefs.profile().copy(username = "local401", authUser = "auth401", password = "LOCAL_TEMPLATE_ONE", domain = "attempted.invalid", server = "attempted.invalid"))
                check(prefs.profile().username == "local401" && prefs.profile().authUser == "auth401" && prefs.profile().password == "LOCAL_TEMPLATE_ONE")
                check(prefs.profile().domain == "127.0.0.1" && prefs.profile().server == "127.0.0.1:5090")
                service.selectAccount("managed:template-home")
                check(prefs.profile().username.isEmpty() && prefs.profile().password.isEmpty())
                prefs.save(prefs.profile().copy(username = "local402", authUser = "auth402", password = "LOCAL_TEMPLATE_TWO"))
                service.selectAccount("managed:template-office")
                check(prefs.profile().password == "LOCAL_TEMPLATE_ONE")
                check(raw.data.all.values.none { it.toString().contains("LOCAL_TEMPLATE_ONE") || it.toString().contains("LOCAL_TEMPLATE_TWO") || it.toString().contains("IGNORED_POLICY_SECRET") })
            }
            publish("local_credentials_changed")
            await("Updated template registrar did not apply") { main { prefs.profile().server == "127.0.0.1:5091" } }
            check(prefs.profile().username == "local401" && prefs.profile().password == "LOCAL_TEMPLATE_ONE")
            check(Preferences(context).profile().authUser == "auth401" && Preferences(context).profile().password == "LOCAL_TEMPLATE_ONE")
            main {
                service.selectAccount("managed:template-home")
                check(prefs.profile().username == "local402" && prefs.profile().password == "LOCAL_TEMPLATE_TWO")
                editor.finish()
            }
            publish("remove")
            await("Policy removal did not restore local settings") { main { !ManagedConfiguration.present && prefs.profile().username == "local200" } }
            check(prefs.profile().password == "LOCAL_FIXTURE_SECRET")
        } finally {
            publish("remove")
            main {
                raw.data.edit().apply {
                    clear()
                    original.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                    } }
                }.commit()
                activity.sip?.applyManagedConfiguration()
                if (!activity.isDestroyed) activity.finish()
            }
        }
    }
}
