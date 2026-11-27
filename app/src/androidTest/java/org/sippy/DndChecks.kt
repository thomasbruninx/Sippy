package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.linphone.core.Core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/** Verify actual SIP packets and UI state, with no external SIP account or server. */
internal class DndChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(40) }
        error(message)
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    fun run() {
        check(DndSettings() == DndSettings.decode(null))
        check(DndSettings().declines(true, "emergency"))
        check(!DndSettings(priorityException = true).declines(true, " Emergency "))
        check(DndSettings(priorityException = true).declines(true, "urgent"))
        check(!DndSettings(show = false).declines(true, null))
        check(DndXml.presence("sip:a&b@example.com", true).contains("a&amp;b"))
        check(DndXml.presence("sip:201@example.com", true).contains("<rpid:busy/>"))
        check(DndXml.featureState(DndXml.featureEvent("sip:201@example.com", true)) == true)
        check(DndXml.featureState("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><DoNotDisturbEvent/>") == null)
        check(DndXml.featureState("<DoNotDisturbEvent><doNotDisturbOn>true</doNotDisturbOn></DoNotDisturbEvent>") == null)
        val prefs = Preferences(instrumentation.targetContext)
        val original = prefs.data.all
        var activity: MainActivity? = null
        AccountChecks.Registrar("127.0.0.1", "DND_TEST_SECRET").use { server ->
            try {
                main {
                    prefs.data.edit().clear().commit()
                    prefs.save(SipProfile(username = "201", domain = "127.0.0.1", server = "127.0.0.1:${server.socket.localPort}", password = "DND_TEST_SECRET"))
                    prefs.renameAccount("DND test")
                }
                activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val app = activity!!
                await("SIP registration failed") { main { app.sipState.ready } }
                val service = main { app.sip!! }
                val core = main { SipService::class.java.getDeclaredField("core").apply { isAccessible = true }.get(service) as Core }
                val id = prefs.selectedAccount().id
                fun configure(config: DndSettings) = main { prefs.saveDnd(config); service.updateDndBehaviour() }
                fun inbound(priority: String? = null, allowed: Boolean = false): String {
                    val host = InetAddress.getByName("127.0.0.1")
                    val port = main { core.transportsUsed.udpPort }
                    DatagramSocket(0, host).use { socket ->
                        socket.soTimeout = 10000
                        val token = UUID.randomUUID().toString()
                        val from = "From: <sip:test-caller@127.0.0.1>;tag=$token"
                        val via = "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$token;rport"
                        val sdp = "v=0\r\no=test 1 1 IN IP4 127.0.0.1\r\ns=DND\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 19002 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n"
                        val headers = mutableListOf("INVITE sip:201@127.0.0.1:$port SIP/2.0", via, from, "To: <sip:201@127.0.0.1>", "Call-ID: $token", "CSeq: 1 INVITE", "Max-Forwards: 70", "Contact: <sip:test-caller@127.0.0.1:${socket.localPort}>")
                        if (priority != null) headers.add("Priority: $priority")
                        headers.addAll(listOf("Content-Type: application/sdp", "Content-Length: ${sdp.toByteArray().size}", "", sdp))
                        val request = headers.joinToString("\r\n").toByteArray()
                        socket.send(DatagramPacket(request,request.size,host,port))
                        if (allowed) {
                            await("Allowed incoming call did not reach the account") { main { service.state.lines.any { it.phase == Phase.INCOMING && it.accountName == "DND test" } } }
                            main { service.state.lines.forEach { service.end(it.id) } }
                        }
                        var response: String
                        do { val packet = DatagramPacket(ByteArray(16384),16384); socket.receive(packet); response=String(packet.data,0,packet.length) } while (response.startsWith("SIP/2.0 1"))
                        check(response.startsWith("SIP/2.0 603")) { "Unexpected incoming call result: ${response.lineSequence().first()}" }
                        val to = response.lineSequence().first { it.startsWith("To:",true) }.trim()
                        val ack = listOf("ACK sip:201@127.0.0.1:$port SIP/2.0",via,from,to,"Call-ID: $token","CSeq: 1 ACK","Content-Length: 0","", "").joinToString("\r\n").toByteArray()
                        socket.send(DatagramPacket(ack,ack.size,host,port))
                        await("Incoming call was not cleared") { main { service.state.lines.isEmpty() } }
                        return response
                    }
                }
                main { service.setDoNotDisturb(true) }
                check(!Regex("(?im)^Reason:").containsMatchIn(inbound()))
                configure(DndSettings(reasonHeader = true, priorityException = true))
                check(Regex("(?im)^Reason:.*cause=603.*Do Not Disturb").containsMatchIn(inbound("urgent")))
                inbound("emergency", allowed = true)
                configure(DndSettings(show = false))
                inbound(allowed = true)
                main {
                    views(app.window.decorView).first { it.contentDescription == "Dialer" }.performClick()
                    check(views(app.window.decorView).none { it.contentDescription?.startsWith("Do Not Disturb ") == true })
                }
                configure(DndSettings(serverSync = DndServerSync.PUBLISH))
                main { service.setDoNotDisturb(false) }
                await("Available PIDF was not published") { server.publishBody.contains("Available") }
                main { service.setDoNotDisturb(true) }
                await("Busy RPID was not published") { server.publishBody.contains("<rpid:busy/>") && server.publishBody.contains("Do Not Disturb") }
                check(server.received["PUBLISH"].orEmpty().contains("Event: presence", true))
                check(server.received["PUBLISH"].orEmpty().contains("SIP-If-Match: fixture-tag", true))
                main { service.setDoNotDisturb(false) }
                await("DND off was not published") { server.publishBody.contains("Available") && !server.publishBody.contains("<rpid:busy/>") }
                configure(DndSettings(serverSync = DndServerSync.FAC, facMethod = DndFacMethod.MESSAGE))
                await("FAC MESSAGE disable was not sent") { server.received["MESSAGE"].orEmpty().lineSequence().first().contains("*79") }
                main { service.setDoNotDisturb(true) }
                await("FAC MESSAGE enable was not sent") { server.received["MESSAGE"].orEmpty().lineSequence().first().contains("*78") }
                check(main { service.state.lines.isEmpty() })
                main { service.setDoNotDisturb(false) }
                configure(DndSettings(serverSync = DndServerSync.FAC, facMethod = DndFacMethod.INVITE))
                await("Silent FAC INVITE did not finish") { server.invite.lineSequence().first().contains("*79") && main { core.calls.isEmpty() } }
                check(server.invite.contains("a=inactive"))
                if (android.os.Build.VERSION.SDK_INT >= 30) check(main { service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0 })
                main { service.dial("999") }
                await("Test call did not start") { main { service.state.lines.isNotEmpty() } }
                main { service.setDoNotDisturb(true) }
                check(main { service.dndSyncStatus(id).contains("pending") })
                main { service.state.lines.forEach { service.end(it.id) } }
                await("Queued FAC INVITE was not sent after the call") { server.invite.lineSequence().first().contains("*78") && main { core.calls.isEmpty() } }
                check(main { service.state.lines.isEmpty() })
                main { service.setDoNotDisturb(false) }
                await("FAC disable did not finish") { server.invite.lineSequence().first().contains("*79") && main { core.calls.isEmpty() } }
                server.serverDnd = false
                configure(DndSettings(serverSync = DndServerSync.AS_FEATURE_EVENT))
                await("Feature event subscription did not synchronize") { main { service.dndSyncStatus(id).contains("synchronized") } }
                main { service.setDoNotDisturb(true) }
                await("BroadSoft SetDoNotDisturb was not sent") { server.serverDnd && server.received["SUBSCRIBE"].orEmpty().contains("<SetDoNotDisturb") }
                await("BroadSoft NOTIFY was not applied") { main { service.dndSyncStatus(id).contains("synchronized") } }
                val subscriptions = server.subscriptions
                server.notifyDnd(false)
                await("Server-side DND change was not reflected locally") { !prefs.dndEnabled(id) && main { id !in service.state.dndAccounts } }
                check(server.subscriptions == subscriptions) { "Server NOTIFY caused a subscription feedback loop" }
                configure(DndSettings(serverSync = DndServerSync.AS_FEATURE_EVENT, featureSchema = DndFeatureSchema.THREE_CX))
                main { service.setDoNotDisturb(true) }
                await("3CX DoNotDisturbEvent NOTIFY was not sent") {
                    server.received["NOTIFY"].orEmpty().contains("<DoNotDisturbEvent") && server.received["NOTIFY"].orEmpty().contains("/csta/ed5")
                }
                val backup = SipSettingsJson.decode(SipSettingsJson.encode(prefs.profile(),prefs.codecs(),false,"DND test",prefs.selectedAccount().dnd))
                check(backup.dnd.featureSchema == DndFeatureSchema.THREE_CX)
                main { service.setDoNotDisturb(false) }
                configure(DndSettings())
            } finally {
                main {
                    activity?.sip?.state?.lines?.forEach { activity?.sip?.end(it.id) }
                    prefs.data.edit().apply { clear(); original.forEach { (k,v) -> when(v) {
                        is String -> putString(k,v); is Boolean -> putBoolean(k,v); is Int -> putInt(k,v); is Long -> putLong(k,v); is Float -> putFloat(k,v)
                    } } }.commit()
                    activity?.sip?.configure(); activity?.finish()
                }
            }
        }
    }
}
