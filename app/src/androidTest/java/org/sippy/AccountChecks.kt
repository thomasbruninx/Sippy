package org.sippy

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.linphone.core.Core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Two real local SIP registrars verify concurrent registration, credentials and call routing. */
internal class AccountChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(40) }
        error(message)
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    internal class Registrar(val host: String, private val secret: String) : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName(host)).apply { soTimeout = 500 }
        @Volatile var registered = false
        @Volatile var requests = 0
        @Volatile var authRejected = false
        @Volatile var invite = ""
        val received = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var serverDnd = false
        @Volatile var publishBody = ""
        @Volatile var messageBody = ""
        private var notifySequence = 1
        @Volatile private var subscription: Triple<String, InetAddress, Int>? = null
        @Volatile var subscriptions = 0
        @Volatile private var closed = false
        private val worker = thread(isDaemon = true) {
            while (!closed) runCatching {
                val packet = DatagramPacket(ByteArray(16384), 16384); socket.receive(packet)
                val request = String(packet.data, 0, packet.length)
                val method = request.substringBefore(' ')
                if (method !in setOf("REGISTER", "INVITE", "CANCEL", "PUBLISH", "SUBSCRIBE", "MESSAGE", "BYE", "NOTIFY")) return@runCatching
                received[method] = request
                val register = request.startsWith("REGISTER ")
                val incomingInvite = request.startsWith("INVITE ")
                val fac = incomingInvite && (request.substringBefore(' ').isNotEmpty()) && request.lineSequence().first().contains(Regex("sip:(?:\\*|%2[Aa])(?:78|79)@"))
                if (register) requests++
                val needsAuth = method in setOf("REGISTER", "INVITE", "PUBLISH", "SUBSCRIBE", "MESSAGE")
                val authorized = !needsAuth || validAuth(request)
                if (register && request.contains("Authorization:") && !authorized) authRejected = true
                if (register && authorized && !request.contains("Expires: 0")) registered = true
                if (incomingInvite) invite = request
                val code = if (!authorized) "401 Unauthorized" else if (incomingInvite && !fac) "180 Ringing" else "200 OK"
                val headers = request.lineSequence().filter { line -> listOf("Via:", "From:", "To:", "Call-ID:", "CSeq:", "Contact:").any { line.startsWith(it, true) } }.map { it.trim() }.toList()
                val extra = if (!authorized) "WWW-Authenticate: Digest realm=\"sippy-test\", nonce=\"fixture-nonce\", algorithm=MD5, qop=\"auth\"\r\n" else "Expires: 600\r\n"
                val replyHeaders = headers.filterNot { it.startsWith("Contact:", true) && method == "SUBSCRIBE" }.map {
                    if (it.startsWith("To:", true) && !it.contains(";tag=")) "$it;tag=fixture" else it
                }
                val body = if (fac) "v=0\r\no=fac 1 1 IN IP4 $host\r\ns=FAC\r\nc=IN IP4 $host\r\nt=0 0\r\nm=audio 19004 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=inactive\r\n" else ""
                val specific = when (method) {
                    "PUBLISH" -> "SIP-ETag: fixture-tag\r\n"
                    "SUBSCRIBE" -> "Contact: <sip:201@$host:${socket.localPort}>\r\n"
                    else -> if (fac) "Content-Type: application/sdp\r\n" else ""
                }
                val response = ("SIP/2.0 $code\r\n" + replyHeaders.joinToString("\r\n") + "\r\n" + extra + specific + "Content-Length: ${body.toByteArray().size}\r\n\r\n" + body).toByteArray()
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                if (!authorized) return@runCatching
                if (method == "NOTIFY") Regex("<doNotDisturbOn>(true|false)</doNotDisturbOn>").find(request)?.let { serverDnd = it.groupValues[1] == "true" }
                if (method == "PUBLISH") publishBody = request.substringAfter("\r\n\r\n", "")
                if (method == "MESSAGE") messageBody = request.substringAfter("\r\n\r\n", "")
                if (method == "SUBSCRIBE") {
                    val change = Regex("<doNotDisturbOn>(true|false)</doNotDisturbOn>").find(request)?.groupValues?.get(1)
                    if (change != null) serverDnd = change == "true"
                    subscription = Triple(request, packet.address, packet.port)
                    subscriptions++
                    notifyDnd(serverDnd)
                }
            }
        }
        @Synchronized fun notifyDnd(value: Boolean) {
            serverDnd = value
            val (request, remote, port) = subscription ?: return
                    fun header(name: String) = request.lineSequence().first { it.startsWith("$name:", true) }.substringAfter(':').trim()
                    val contact = header("Contact").substringAfter('<').substringBefore('>')
                    val xml = "<DoNotDisturbEvent xmlns=\"http://www.ecma-international.org/standards/ecma-323/csta/ed3\"><device>sip:201@$host</device><doNotDisturbOn>$serverDnd</doNotDisturbOn></DoNotDisturbEvent>"
                    val notify = ("NOTIFY $contact SIP/2.0\r\nVia: SIP/2.0/UDP $host:${socket.localPort};branch=z9hG4bK${java.util.UUID.randomUUID()}\r\nFrom: ${header("To").let { if (it.contains(";tag=")) it else "$it;tag=fixture" }}\r\nTo: ${header("From")}\r\nCall-ID: ${header("Call-ID")}\r\nCSeq: ${notifySequence++} NOTIFY\r\nMax-Forwards: 70\r\nEvent: as-feature-event\r\nSubscription-State: active;expires=600\r\nContact: <sip:201@$host:${socket.localPort}>\r\nContent-Type: application/x-as-feature-event+xml\r\nContent-Length: ${xml.toByteArray().size}\r\n\r\n$xml").toByteArray()
                    socket.send(DatagramPacket(notify, notify.size, remote, port))
        }
        private fun validAuth(request: String): Boolean {
            val auth = request.lineSequence().firstOrNull { it.startsWith("Authorization:", true) } ?: return false
            val values = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").findAll(auth).associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            val ha1 = md5("201:sippy-test:$secret"); val ha2 = md5("${request.substringBefore(' ')}:${values["uri"]}")
            val expected = if (values["qop"] == "auth") md5("$ha1:fixture-nonce:${values["nc"]}:${values["cnonce"]}:auth:$ha2") else md5("$ha1:fixture-nonce:$ha2")
            return values["response"] == expected
        }
        override fun close() { closed = true; socket.close(); worker.join(1000) }
    }
    fun run() {
        val context = instrumentation.targetContext
        check(!ManagedConfiguration.present) { "Run account checks without an OS managed policy." }
        val prefs = Preferences(context)
        val original = prefs.data.all
        var activity: MainActivity? = null
        Registrar("127.0.0.1", "ACCOUNT_ONE_SECRET").use { first -> Registrar("127.0.0.2", "ACCOUNT_TWO_SECRET").use { second ->
            try {
                main {
                    prefs.data.edit().clear().commit()
                    prefs.save(SipProfile(username = "201", domain = first.host, server = "${first.host}:${first.socket.localPort}", password = "ACCOUNT_ONE_SECRET"))
                    // Simulate upgrading from the flat preference format.
                    prefs.data.edit().remove("sipAccountsLocal").commit()
                    check(prefs.profile().password == "ACCOUNT_ONE_SECRET")
                    prefs.renameAccount("Office")
                }
                val firstId = prefs.selectedAccount().id
                val secondId = main { prefs.addAccount("Home", SipProfile(username = "201", domain = second.host,
                    server = "${second.host}:${second.socket.localPort}", password = "ACCOUNT_TWO_SECRET")) }
                check(!prefs.data.getString("sipAccountsLocal", "")!!.contains("ACCOUNT_ONE_SECRET"))
                check(!prefs.data.getString("sipAccountsLocal", "")!!.contains("ACCOUNT_TWO_SECRET"))
                check(runCatching { prefs.addAccount("office") }.isFailure)
                val backup = SipSettingsJson.decode(SipSettingsJson.encode(prefs.profile(), prefs.codecs(), true, "Home"))
                check(backup.name == "Home" && backup.profile.password == "ACCOUNT_TWO_SECRET")
                check(SipSettingsJson.decode(SipSettingsJson.encode(prefs.profile(), prefs.codecs(), false)).name == null)
                check(prefs.accounts().size == 2) { "Second account was not stored: ${prefs.accounts().map { it.name }}" }
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val app = activity!!
                await("SIP service did not connect") { main { app.sip != null } }
                val service = main { app.sip!! }
                try {
                    await("Both accounts did not register with their own credentials") { first.registered && second.registered && main { service.state.accounts.count { it.ready } == 2 } }
                } catch (error: Throwable) {
                    error("${error.message}; Office requests=${first.requests} registered=${first.registered} badAuth=${first.authRejected}; Home requests=${second.requests} registered=${second.registered} badAuth=${second.authRejected}; statuses=${main { service.state.accounts }}")
                }
                val coreField = SipService::class.java.getDeclaredField("core").apply { isAccessible = true }
                val core = main { coreField.get(service) as Core }
                val pointer = core.nativePointer
                main { service.dial("345") }
                await("Outgoing call did not use selected Home account") { second.invite.startsWith("INVITE sip:345@127.0.0.2") && main { service.state.lines.any { it.accountName == "Home" } } }
                main { service.selectAccount(firstId) }
                check(main { (coreField.get(service) as Core).nativePointer == pointer && service.state.lines.all { it.accountName == "Home" } })
                check(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    val text = it.notification.extras.getString("android.text").orEmpty()
                    text.contains("Home") && text.contains("Outgoing: Office")
                }) { "Active notification did not identify the call and selected outgoing accounts" }
                main { service.state.lines.forEach { service.end(it.id) } }
                await("Outgoing call did not end") { main { service.state.lines.isEmpty() } }
                val port = main { core.transportsUsed.udpPort }
                main { service.selectAccount(secondId); service.setDoNotDisturb(true); service.selectAccount(firstId) }
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { caller ->
                    caller.soTimeout = 10000
                    val id = UUID.randomUUID().toString()
                    val sdp = "v=0\r\no=test 1 1 IN IP4 127.0.0.1\r\ns=DND\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 19002 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n"
                    val request = listOf("INVITE sip:201@127.0.0.2:$port SIP/2.0", "Via: SIP/2.0/UDP 127.0.0.1:${caller.localPort};branch=z9hG4bK$id;rport", "From: <sip:caller@127.0.0.1>;tag=$id", "To: <sip:201@127.0.0.2>", "Call-ID: $id", "CSeq: 1 INVITE", "Max-Forwards: 70", "Contact: <sip:caller@127.0.0.1:${caller.localPort}>", "Content-Type: application/sdp", "Content-Length: ${sdp.toByteArray().size}", "", sdp).joinToString("\r\n").toByteArray()
                    caller.send(DatagramPacket(request, request.size, InetAddress.getByName("127.0.0.1"), port))
                    var response: String
                    do { val packet = DatagramPacket(ByteArray(8192),8192); caller.receive(packet); response = String(packet.data,0,packet.length) } while (response.startsWith("SIP/2.0 1"))
                    check(response.startsWith("SIP/2.0 603")) { "DND did not decline incoming call: ${response.lineSequence().first()}" }
                }
                await("DND left an incoming call") { main { service.state.lines.isEmpty() } }
                val notices = context.getSystemService(NotificationManager::class.java).activeNotifications
                check(notices.any { it.notification.extras.getString("android.title").orEmpty().contains("DND: Home") })
                fun exported(accountId: String): org.json.JSONObject {
                    val records = main { SettingsBackup(context, prefs).export(BackupSection.entries.toSet()).root.getJSONArray("accounts") }
                    return (0 until records.length()).map(records::getJSONObject).first { it.getString("id") == accountId }
                }
                val homeExport = exported(secondId)
                check(homeExport.getString("name") == "Home" && homeExport.getJSONObject("account").getString("password") == "ACCOUNT_TWO_SECRET")
                // Drive the actual import dialog: empty names are rejected, and an older backup creates a new account.
                val importFile = java.io.File(context.cacheDir, "account-import-${UUID.randomUUID()}.json")
                try {
                    importFile.writeText(SipSettingsJson.encode(SipProfile(username = "222", domain = "127.0.0.1", register = false), prefs.codecs(), false))
                    main { app.settingsFiles.result(43, android.app.Activity.RESULT_OK, Intent().setData(android.net.Uri.fromFile(importFile))) }
                    fun dialogNodes() = instrumentation.uiAutomation.rootInActiveWindow
                    fun importButton() = dialogNodes()?.findAccessibilityNodeInfosByText("Import")?.firstOrNull { it.text?.toString()?.equals("Import", true) == true }
                    await("Import account-name dialog did not open") { importButton() != null }
                    val emptyName = dialogNodes()!!.findAccessibilityNodeInfosByText("Account name").first { it.className == "android.widget.EditText" }
                    emptyName.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    })
                    importButton()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    check(prefs.accounts().size == 2) { "Import accepted an empty account name" }
                    val field = dialogNodes()!!.findAccessibilityNodeInfosByText("Account name").first { it.className == "android.widget.EditText" }
                    field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Imported")
                    })
                    importButton()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    await("Named import did not create an account") { prefs.accounts().any { it.name == "Imported" } }
                    check(prefs.accounts().size == 3 && prefs.selectedAccount().name == "Imported")
                    main { prefs.removeAccount(); service.configure(); service.selectAccount(firstId) }
                } finally { importFile.delete() }
                main {
                    service.selectAccount(secondId); service.setDoNotDisturb(false); service.selectAccount(firstId)
                    app.settingsTab = 0; app.openLdapSettings()
                    val fields = views(app.window.decorView).filterIsInstance<EditText>()
                    val password = fields.first { it.contentDescription == "Password" }
                    check(password.transformationMethod != null)
                    views(app.window.decorView).first { it.contentDescription == "Show Password" }.performClick()
                    check(password.transformationMethod == null)
                    views(app.window.decorView).first { it.contentDescription == "Hide Password" }.performClick()
                    check(password.transformationMethod != null)
                    check(views(app.window.decorView).none { it.contentDescription == "Show SIP domain" })
                }
                fun managed(id: String, name: String) = Bundle().apply {
                    putString("id", id); putString("name", name); putString("accountUsername", if (id == "one") "300" else "301")
                    putString("accountDomain", "127.0.0.1"); putBoolean("accountRegister", false); putString("accountPassword", "MANAGED_SECRET_$id")
                }
                main {
                    ManagedConfiguration.apply(context, Bundle().apply {
                        putBoolean("managedConfigurationEnabled", true)
                        putParcelableArray("sipAccounts", arrayOf(managed("one", "Managed one"), managed("two", "Managed two")))
                    })
                    check(ManagedConfiguration.error == null)
                    service.selectAccount("managed:one")
                    check(prefs.isManaged("password"))
                    prefs.save(prefs.profile().copy(username = "attempted", password = "attempted", displayName = "User editable name"))
                    check(prefs.profile().username == "300" && prefs.profile().password == "MANAGED_SECRET_one")
                    check(prefs.profile().displayName == "User editable name" && !prefs.isManaged("displayName"))
                    prefs.saveDnd(DndSettings(reasonHeader = true))
                    check(prefs.selectedAccount().dnd.reasonHeader && !prefs.isManaged("dndReasonHeader"))
                    service.selectAccount("managed:two")
                    check(prefs.profile().password == "MANAGED_SECRET_two")
                    check(prefs.data.all.values.none { it.toString().contains("MANAGED_SECRET") })
                    check(prefs.accounts().any { it.id == "managed:one" })
                }
                val managedExport = exported("managed:one")
                check(!managedExport.getJSONObject("account").has("password") && !managedExport.toString().contains("MANAGED_SECRET"))
                main {
                    ManagedConfiguration.apply(context, Bundle.EMPTY)
                    check(prefs.accounts().size == 2 && prefs.accounts().none { it.managed })
                    prefs.selectAccount(secondId)
                    check(prefs.profile().password == "ACCOUNT_TWO_SECRET")
                }
            } finally {
                main {
                    ManagedConfiguration.apply(context, Bundle.EMPTY)
                    activity?.sip?.state?.lines?.forEach { activity?.sip?.end(it.id) }
                    prefs.data.edit().apply {
                        clear(); original.forEach { (k,v) -> when (v) {
                            is String -> putString(k,v); is Boolean -> putBoolean(k,v); is Int -> putInt(k,v); is Long -> putLong(k,v); is Float -> putFloat(k,v)
                        } }
                    }.commit()
                    activity?.sip?.configure(); activity?.finish()
                }
            }
        } }
    }
}
