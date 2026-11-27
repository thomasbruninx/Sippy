package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import org.linphone.core.Core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

internal class AccountCodecChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>?=null
        instrumentation.runOnMainSync { result=runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message:String, condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(40)}
        error(message)
    }
    private fun audio(sdp:String) = sdp.lineSequence().first { it.startsWith("m=audio") }.trim().split(' ').drop(3).filter { it in setOf("0","8","9","96","111") }
    fun run() {
        val prefs=Preferences(instrumentation.targetContext)
        val original=prefs.localSnapshot()
        var activity:MainActivity?=null
        AccountChecks.Registrar("127.0.0.1","CODEC_TEST_SECRET").use { server ->
            try {
                main {
                    prefs.restoreSnapshot(emptyMap<String,Any>())
                    prefs.save(SipProfile(username="201",domain="127.0.0.1",server="127.0.0.1:${server.socket.localPort}",register=false,password="CODEC_TEST_SECRET"))
                    prefs.renameAccount("PCMU account");prefs.saveCodecs(AccountOptions.codecOrder("pcmu"))
                }
                val first=prefs.selectedAccount().id
                val second=main { prefs.addAccount("PCMA account",SipProfile(username="201",domain="127.0.0.2",server="127.0.0.1:${server.socket.localPort}",register=false,password="CODEC_TEST_SECRET"),
                    options=AccountOptions(codecs=AccountOptions.codecOrder("pcma"),transferMode=TransferMode.ATTENDED_ONLY)) }
                activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val app=activity!!
                await("SIP service did not start"){main{app.sipState.ready}}
                val service=main{app.sip!!}
                val core=main{SipService::class.java.getDeclaredField("core").apply{isAccessible=true}.get(service) as Core}
                main{service.dial("999")}
                await("PCMA outgoing offer not sent"){server.invite.contains("sip:999@127.0.0.2")}
                check(audio(server.invite)==listOf("8")){"Outgoing account offered wrong codecs: ${audio(server.invite)}"}
                main { service.selectAccount(first) }
                val call=main{service.state.lines.first().id}
                check(main{service.transferMode(call)}==TransferMode.ATTENDED_ONLY){"Transfer mode followed outgoing selection instead of the call's account"}
                main{service.end(call)}
                await("Outgoing call not ended"){main{service.state.lines.isEmpty()}}
                main{service.dial("998")}
                await("PCMU outgoing offer not sent"){server.invite.contains("sip:998@127.0.0.1")}
                check(audio(server.invite)==listOf("0")){"PCMU outgoing offer leaked other account codecs"}
                main{service.state.lines.forEach{service.end(it.id)}}
                await("Outgoing call not ended"){main{service.state.lines.isEmpty()}}
                val host=InetAddress.getByName("127.0.0.1")
                val port=main{core.transportsUsed.udpPort}
                DatagramSocket(0,host).use { socket ->
                    socket.soTimeout=10000
                    val id=UUID.randomUUID().toString()
                    val via="Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id;rport"
                    val from="From: <sip:codec-caller@127.0.0.1>;tag=$id"
                    val sdp="v=0\r\no=test 1 1 IN IP4 127.0.0.1\r\ns=Codec check\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 19002 RTP/AVP 0 8\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=sendrecv\r\n"
                    val request=listOf("INVITE sip:201@127.0.0.2:$port SIP/2.0",via,from,"To: <sip:201@127.0.0.2>","Call-ID: $id","CSeq: 1 INVITE","Max-Forwards: 70","Contact: <sip:codec-caller@127.0.0.1:${socket.localPort}>","Content-Type: application/sdp","Content-Length: ${sdp.toByteArray().size}","",sdp).joinToString("\r\n").toByteArray()
                    socket.send(DatagramPacket(request,request.size,host,port))
                    await("Incoming call did not map to the PCMA account"){main{service.state.lines.any{it.accountName=="PCMA account"&&it.phase==Phase.INCOMING}}}
                    val incoming=main{service.state.lines.first().id}
                    main{service.answer(incoming)}
                    var response:String
                    do{val packet=DatagramPacket(ByteArray(16384),16384);socket.receive(packet);response=String(packet.data,0,packet.length)}while(!response.startsWith("SIP/2.0 200"))
                    check(audio(response)==listOf("8")){"Incoming PCMA answer offered other account codecs: ${audio(response)}"}
                    val to=response.lineSequence().first{it.startsWith("To:",true)}.trim()
                    val ack=listOf("ACK sip:201@127.0.0.2:$port SIP/2.0",via,from,to,"Call-ID: $id","CSeq: 1 ACK","Content-Length: 0","","").joinToString("\r\n").toByteArray()
                    socket.send(DatagramPacket(ack,ack.size,host,port))
                    main{service.end(incoming)}
                }
                check(prefs.accounts().first{it.id==first}.options.codecs.first().id=="pcmu")
                check(prefs.accounts().first{it.id==second}.options.codecs.first().id=="pcma")
            } finally {
                main{activity?.sip?.state?.lines?.forEach{activity?.sip?.end(it.id)};prefs.restoreSnapshot(original);activity?.sip?.configure();activity?.finish()}
            }
        }
    }
}
