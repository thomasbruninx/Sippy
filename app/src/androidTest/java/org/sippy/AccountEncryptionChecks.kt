package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.linphone.core.Core
import org.linphone.core.Call
import org.linphone.core.CoreListenerStub
import org.linphone.core.MediaEncryption
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class AccountEncryptionChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 18000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(35) }
        error(message)
    }
    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val media = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val invites = CopyOnWriteArrayList<String>()
        val infos = CopyOnWriteArrayList<String>()
        @Volatile var reject = false
        @Volatile var clearAnswer = false
        private val thread = Thread {
            while (!socket.isClosed) try {
                val packet = DatagramPacket(ByteArray(65536), 65536); socket.receive(packet)
                val request = String(packet.data, 0, packet.length)
                val method = request.substringBefore(' ')
                if (method == "ACK" || request.startsWith("SIP/2.0")) continue
                if (method == "INVITE") invites.add(request)
                if (method == "INFO") infos.add(request)
                val code = if (method == "INVITE" && reject) "488 Not Acceptable Here" else "200 OK"
                val body = if (method == "INVITE" && !reject) sdp(if (clearAnswer) "" else request, media.localPort) else ""
                fun h(name: String) = request.lineSequence().first { it.startsWith("$name:", true) }.trim()
                val to = h("To").let { if (";tag=" in it) it else "$it;tag=peer" }
                val response = listOf("SIP/2.0 $code", h("Via"), h("From"), to, h("Call-ID"), h("CSeq"),
                    "Contact: <sip:peer@127.0.0.1:${socket.localPort}>", "Content-Type: application/sdp",
                    "Content-Length: ${body.toByteArray().size}", "", body).joinToString("\r\n").toByteArray()
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); media.close(); thread.join(2000) }
    }
    companion object {
        private val key = Base64.encodeToString(ByteArray(30) { (it + 1).toByte() }, Base64.NO_WRAP)
        private fun sdp(offer: String, port: Int): String {
            val secure = offer.contains("RTP/SAVP")
            val crypto = offer.lineSequence().firstOrNull { it.startsWith("a=crypto:") && "AES_CM_128_HMAC_SHA1_80" in it }
            val tag = crypto?.substringAfter("a=crypto:")?.substringBefore(' ') ?: "1"
            val direction = if (offer.contains("a=sendonly")) "recvonly" else if (offer.contains("a=inactive")) "inactive" else "sendrecv"
            return "v=0\r\no=peer 1 1 IN IP4 127.0.0.1\r\ns=Encryption check\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n" +
                "m=audio $port RTP/${if (secure) "SAVP" else "AVP"} 0\r\na=rtpmap:0 PCMU/8000\r\n" +
                (if (secure) "a=crypto:$tag AES_CM_128_HMAC_SHA1_80 inline:$key\r\n" else "") + "a=$direction\r\n"
        }
    }
    private class Incoming(private val port: Int, domain: String, secure: Boolean) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val id = UUID.randomUUID().toString()
        private val uri = "sip:201@$domain:$port"
        private val via = "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id;rport"
        private val from = "From: <sip:remote@127.0.0.1>;tag=$id"
        init {
            socket.soTimeout = 10000
            val body = sdp(if (secure) "RTP/SAVP" else "", 19500)
            val request = listOf("INVITE $uri SIP/2.0", via, from, "To: <sip:201@$domain>", "Call-ID: $id", "CSeq: 1 INVITE", "Max-Forwards: 70",
                "Contact: <sip:remote@127.0.0.1:${socket.localPort}>", "Content-Type: application/sdp", "Content-Length: ${body.toByteArray().size}", "", body).joinToString("\r\n")
            send(request)
        }
        private fun send(text: String) { val bytes=text.toByteArray();socket.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),port)) }
        fun finalResponse(): String {
            while (true) {
                val packet=DatagramPacket(ByteArray(65536),65536);socket.receive(packet)
                val text=String(packet.data,0,packet.length)
                if (text.startsWith("SIP/2.0") && !text.startsWith("SIP/2.0 1")) return text
            }
        }
        fun ack(response: String) {
            val to=response.lineSequence().first { it.startsWith("To:",true) }.trim()
            send(listOf("ACK $uri SIP/2.0",via,from,to,"Call-ID: $id","CSeq: 1 ACK","Content-Length: 0","","").joinToString("\r\n"))
        }
        override fun close() = socket.close()
    }
    fun run() {
        val context=instrumentation.targetContext
        val prefs=Preferences(context)
        val original=prefs.localSnapshot()
        var app:MainActivity?=null
        Peer().use { peer ->
            try {
                // Migrate existing named accounts from the old shared encryption value exactly once.
                main {
                    prefs.restoreSnapshot(emptyMap<String,Any>())
                    prefs.save(SipProfile(username="201",domain="127.0.0.1",register=false))
                    prefs.addAccount("Second")
                    val old=JSONArray(prefs.localSnapshot()["sipAccountsLocal"] as String)
                    for(i in 0 until old.length())old.getJSONObject(i).getJSONObject("fields").remove("encryption")
                    prefs.data.edit().putString("sipAccountsLocal",old.toString()).putString("encryption","SRTP").remove("accountEncryptionMigrated").commit()
                    check(prefs.accounts().all { it.profile.encryption==Encryption.SRTP })
                    prefs.data.edit().putString("encryption","None").commit()
                    check(prefs.accounts().all { it.profile.encryption==Encryption.SRTP })
                    prefs.resetLocalSettings()
                    prefs.save(SipProfile(username="201",domain="127.0.0.1",server="127.0.0.1:${peer.socket.localPort}",register=false))
                    prefs.renameAccount("Clear account");prefs.saveCodecs(AccountOptions.codecOrder("pcmu"))
                }
                val clear=prefs.selectedAccount().id
                val secure=main { prefs.addAccount("Secure account",SipProfile(username="201",domain="127.0.0.2",server="127.0.0.1:${peer.socket.localPort}",register=false,encryption=Encryption.SRTP), options=AccountOptions(codecs=AccountOptions.codecOrder("pcmu"))) }
                main { prefs.saveGlobalNetwork(SipProfile(dtmf=Dtmf.INFO)) }
                check(prefs.accounts().all { it.profile.dtmf==Dtmf.INFO })
                app=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val activity=app!!
                await("Accounts did not become ready") { main { activity.sipState.ready } }
                val service=main { activity.sip!! }
                val field=SipService::class.java.getDeclaredField("core").apply { isAccessible=true }
                fun core()=field.get(service) as Core
                check(main { core().useInfoForDtmf && !core().useRfc2833ForDtmf }) {
                    "Global DTMF flags differ from saved INFO setting: " + main { "INFO=${core().useInfoForDtmf}, RTP=${core().useRfc2833ForDtmf}, saved=${prefs.profile().dtmf}" }
                }
                fun native(id:Long)=core().calls.first { it.nativePointer==id }
                fun endAll() { main { service.state.lines.forEach { service.end(it.id) } };await("Calls did not end") { main { service.state.lines.isEmpty() } } }
                main { service.dial("900") }
                await("SRTP call did not connect") { main { service.state.lines.any { it.phase==Phase.ACTIVE } } }
                val encrypted=main { service.state.lines.single().id }
                check(peer.invites.first().contains("RTP/SAVP") && peer.invites.first().contains("a=crypto:"))
                check(main { native(encrypted).currentParams.mediaEncryption==MediaEncryption.SRTP })
                main { service.digits(encrypted,'5') }
                await("Secure call did not use global SIP INFO DTMF") { peer.infos.any { Regex("Signal\\s*=\\s*5",RegexOption.IGNORE_CASE).containsMatchIn(it) } || peer.infos.any { it.substringAfter("\r\n\r\n").trim()=="5" } }
                // Let the peer's 200 OK finish the INFO transaction before a hold re-INVITE.
                Thread.sleep(500)
                main { service.selectAccount(clear);service.dial("901") }
                await("Mixed account calls did not connect") { main { service.state.lines.size==2 && service.state.lines.any { it.accountName=="Clear account"&&it.phase==Phase.ACTIVE } } }
                val plain=main { service.state.lines.first { it.accountName=="Clear account" }.id }
                check(main { native(plain).currentParams.mediaEncryption==MediaEncryption.None && native(encrypted).currentParams.mediaEncryption==MediaEncryption.SRTP })
                check(peer.invites.any { it.startsWith("INVITE sip:901@") && !it.contains("a=crypto:") && it.contains("RTP/AVP") })
                main { service.digits(plain,'6') }
                await("Clear call did not use global SIP INFO DTMF") { peer.infos.any { Regex("Signal\\s*=\\s*6").containsMatchIn(it) } }
                Thread.sleep(500)
                main { service.select(encrypted) }
                await("Encrypted call did not resume") { main { service.state.lines.any { it.id==encrypted && it.phase==Phase.ACTIVE } } }
                check(main { native(encrypted).currentParams.mediaEncryption==MediaEncryption.SRTP && native(plain).currentParams.mediaEncryption==MediaEncryption.None })
                endAll()
                // Incoming calls obey their receiving account, regardless of outgoing selection.
                val port=main { core().transportsUsed.udpPort }
                main { service.selectAccount(clear) }
                Incoming(port,"127.0.0.2",true).use { caller ->
                    await("Secure incoming call did not ring") { main { service.state.lines.any { it.phase==Phase.INCOMING } } }
                    val id=main { service.state.lines.single().id; }.also { main { service.answer(it) } }
                    val response=caller.finalResponse();check(response.startsWith("SIP/2.0 200") && response.contains("RTP/SAVP"))
                    caller.ack(response)
                    await("Secure incoming call did not connect") { main { service.state.lines.any { it.id==id&&it.phase==Phase.ACTIVE } } }
                    check(main { native(id).currentParams.mediaEncryption==MediaEncryption.SRTP })
                    endAll()
                }
                Incoming(port,"127.0.0.2",false).use { caller ->
                    check(caller.finalResponse().startsWith("SIP/2.0 488")) { "Secure account accepted a clear incoming offer" }
                }
                await("Rejected incoming call remained") { main { service.state.lines.isEmpty() } }
                main { service.selectAccount(secure) }
                Incoming(port,"127.0.0.1",false).use { caller ->
                    await("Clear incoming call did not ring") { main { service.state.lines.any { it.phase==Phase.INCOMING } } }
                    val id=main { service.state.lines.single().id }.also { main { service.answer(it) } }
                    val response=caller.finalResponse();check(response.startsWith("SIP/2.0 200") && !response.contains("a=crypto:"))
                    caller.ack(response);await("Clear incoming call did not connect") { main { service.state.lines.any { it.phase==Phase.ACTIVE } } }
                    check(main { native(id).currentParams.mediaEncryption==MediaEncryption.None });endAll()
                }
                peer.reject=true
                val insecureConnection=AtomicBoolean(false)
                val observer=object : CoreListenerStub() {
                    override fun onCallStateChanged(core:Core,call:Call,state:Call.State,message:String) {
                        // Connected marks the SIP dialog before media validation.
                        // StreamsRunning is the point where media can actually flow.
                        if(state==Call.State.StreamsRunning && call.currentParams.mediaEncryption==MediaEncryption.None)
                            insecureConnection.set(true)
                    }
                }
                main { core().addListener(observer) }
                val before=peer.invites.size
                main { service.dial("902") }
                await("Secure offer was not sent") { peer.invites.size>before }
                await("Rejected secure call did not fail") { main { service.state.lines.isEmpty() } }
                Thread.sleep(700)
                check(peer.invites[before].contains("RTP/SAVP")&&peer.invites[before].contains("a=crypto:"))
                peer.reject=false;peer.clearAnswer=true
                val badAnswerStart=peer.invites.size
                main { service.dial("905") }
                await("Bad-answer test did not send an offer") { peer.invites.size>badAnswerStart }
                await("Secure account did not reject an unencrypted answer") { main { service.state.lines.isEmpty() } }
                check(!insecureConnection.get()) { "Secure account started media without encryption" }
                main { core().removeListener(observer) }
                peer.clearAnswer=false;peer.reject=true
                for ((mode,attribute) in listOf(Encryption.DTLS to "a=fingerprint:",Encryption.ZRTP to "a=zrtp-hash:")) {
                    if(!main { core().isMediaEncryptionSupported(MediaEncryption.valueOf(mode.name)) })continue
                    main { prefs.save(prefs.profile().copy(encryption=mode));service.configure() }
                    val previous=peer.invites.size
                    main { service.dial(if (mode==Encryption.DTLS) "903" else "904") }
                    await("$mode offer not sent") { peer.invites.size>previous }
                    check(peer.invites[previous].contains(attribute)) { "$mode offer did not include $attribute" }
                    await("$mode call did not fail cleanly") { main { service.state.lines.isEmpty() } }
                }
            } finally {
                main { app?.sip?.state?.lines?.forEach { app?.sip?.end(it.id) };prefs.restoreSnapshot(original);app?.sip?.configure();app?.finish() }
            }
        }
    }
}
