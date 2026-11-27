package org.sippy

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import org.linphone.core.AudioDevice
import android.os.ParcelFileDescriptor
import android.os.Build
import org.linphone.core.Core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/** Local SIP INVITEs exercise the real service's incoming-call alert path. */
internal class IncomingCallChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        check(condition()) { label }
    }

    private fun vibrationActive(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys vibrator_manager")
        ).bufferedReader().use { it.readText() }
        val current = dump.substringAfter("CurrentVibration:", "").substringBefore("NextVibration:")
        return current.contains("org.sippy")
    }

    fun run() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await("SIP service did not bind") { main { activity.sip != null } }
        val service = main { activity.sip!! }
        check(main { service.state.lines.isEmpty() }) { "End calls before running local SIP checks" }
        check(activity.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL)
        val prefs = activity.prefs
        val originalProfile = prefs.profile()
        val originalOptions = prefs.selectedAccount().options
        val originalUri = originalOptions.ringtoneUri
        val originalVibrate = prefs.data.getBoolean("vibrateOnIncoming", false)
        val originalBackground = prefs.backgroundCalling
        val coreField = SipService::class.java.getDeclaredField("core").apply { isAccessible = true }
        val playerField = IncomingRingtone::class.java.getDeclaredField("player").apply { isAccessible = true }
        val alertGetter = SipService::class.java.getDeclaredMethod("getIncomingRingtone").apply { isAccessible = true }
        fun playing() = main {
            val alert = alertGetter.invoke(service) as IncomingRingtone
            (playerField.get(alert) as? MediaPlayer)?.isPlaying == true
        }
        fun proximityHeld() = main {
            val proximity = SipService::class.java.getDeclaredMethod("getCallProximity").apply { isAccessible = true }.invoke(service)
            val lock = CallProximity::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(proximity) as? PowerManager.WakeLock
            lock?.isHeld == true
        }
        try {
            main {
                prefs.save(SipProfile(username = "ring-check", domain = "127.0.0.1", register = false))
                prefs.backgroundCalling = true
                prefs.saveOptions(prefs.selectedAccount().options.copy(ringtoneUri = null, ringtoneName = "Default ringtone"))
                service.configure()
            }
            val port = main { (coreField.get(service) as Core).transportsUsed.udpPort }
            check(port > 0)
            val loopback = InetAddress.getByName("127.0.0.1")
            for ((index, vibrate) in listOf(false, true, false, false).withIndex()) {
                if (index == 3) {
                    main { activity.finish() }
                    await("The call screen did not close") { main { activity.isDestroyed } }
                    await("Call availability stopped when the activity was closed") {
                        activity.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 900 }
                    }
                }
                main { prefs.vibrateOnIncoming = vibrate }
                DatagramSocket(0, loopback).use { socket ->
                    val id = UUID.randomUUID().toString()
                    val body = listOf("v=0", "o=alert-check 1 1 IN IP4 127.0.0.1", "s=Local alert check",
                        "c=IN IP4 127.0.0.1", "t=0 0", "m=audio 19000 RTP/AVP 0", "a=rtpmap:0 PCMU/8000", "a=sendrecv", "")
                        .joinToString("\r\n")
                    val invite = listOf("INVITE sip:ring-check@127.0.0.1:$port SIP/2.0",
                        "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id;rport",
                        "Max-Forwards: 70", "From: <sip:alert-check@127.0.0.1>;tag=$id",
                        "To: <sip:ring-check@127.0.0.1>", "Call-ID: $id", "CSeq: 1 INVITE",
                        "Contact: <sip:alert-check@127.0.0.1:${socket.localPort}>",
                        "Content-Type: application/sdp", "Content-Length: ${body.toByteArray().size}", "", body)
                        .joinToString("\r\n").toByteArray()
                    socket.send(DatagramPacket(invite, invite.size, loopback, port))
                    await("Local INVITE did not reach the incoming-call screen") {
                        main { service.state.lines.any { it.phase == Phase.INCOMING } }
                    }
                    await("Default ringtone did not play on an incoming call", ::playing)
                    check(vibrationActive() == vibrate) { "Incoming vibration did not match its setting" }
                    val incomingId = main { service.state.lines.first { it.phase == Phase.INCOMING }.id }
                    val notificationManager = activity.getSystemService(NotificationManager::class.java)
                    await("Incoming notification was not published") {
                        notificationManager.activeNotifications.any { it.id == 901 }
                    }
                    val notification = checkNotNull(notificationManager.activeNotifications
                        .firstOrNull { it.id == 901 }?.notification) { "Incoming notification disappeared before its actions were checked" }
                    check(notification.actions.map { it.title.toString() } == listOf("Accept", "Decline"))
                    Thread.sleep(1500)
                    check(vibrationActive() == vibrate) { "Incoming vibration did not continue after the notification appeared" }
                    if (vibrate) {
                        main {
                            prefs.vibrateOnIncoming = false
                            service.updateIncomingVibration()
                        }
                        await("Turning vibration off did not stop the incoming alert") { !vibrationActive() }
                        main {
                            prefs.vibrateOnIncoming = true
                            service.updateIncomingVibration()
                        }
                        await("Turning vibration on did not resume the incoming alert", ::vibrationActive)
                    }
                    if (index == 2) {
                        notification.actions.first { it.title.toString() == "Accept" }.actionIntent.send()
                        socket.soTimeout = 10000
                        var acceptedResponse: String
                        do {
                            val packet = DatagramPacket(ByteArray(8192), 8192)
                            socket.receive(packet)
                            acceptedResponse = String(packet.data, 0, packet.length)
                        } while (!acceptedResponse.startsWith("SIP/2.0 200 "))
                        val to = acceptedResponse.lineSequence().first { it.startsWith("To:", true) }.trim()
                        val ack = listOf("ACK sip:ring-check@127.0.0.1:$port SIP/2.0",
                            "Via: SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id-ack;rport",
                            "Max-Forwards: 70", "From: <sip:alert-check@127.0.0.1>;tag=$id", to,
                            "Call-ID: $id", "CSeq: 1 ACK", "Content-Length: 0", "", "")
                            .joinToString("\r\n").toByteArray()
                        socket.send(DatagramPacket(ack, ack.size, loopback, port))
                        await("Notification Accept did not connect the call") {
                            main { service.state.lines.any { it.id == incomingId && it.phase == Phase.ACTIVE } }
                        }
                        check(!playing() && !vibrationActive()) { "Alerts continued after accepting" }
                        val feedback = MainActivity::class.java.getDeclaredMethod("playCallTone", Char::class.javaPrimitiveType).apply { isAccessible = true }
                        val generator = MainActivity::class.java.getDeclaredField("callToneGenerator").apply { isAccessible = true }
                        val oldTones = prefs.dialpadTones
                        try {
                            main {
                                prefs.dialpadTones = false
                                feedback.invoke(activity, '1')
                                check(generator.get(activity) == null) { "Disabled DTMF feedback played a tone" }
                                prefs.dialpadTones = true
                                for (digit in "12*#") {
                                    service.digits(incomingId, digit)
                                    feedback.invoke(activity, digit)
                                }
                                check(generator.get(activity) != null) { "In-call DTMF feedback did not create audio" }
                                check(service.state.lines.any { it.id == incomingId && it.phase == Phase.ACTIVE })
                            }
                        } finally { main { prefs.dialpadTones = oldTones } }

                        val earpiece = main { (coreField.get(service) as Core).extendedAudioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece } }
                        if (earpiece != null && activity.getSystemService(PowerManager::class.java)
                                .isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                            main { (coreField.get(service) as Core).calls.first().setOutputAudioDevice(earpiece) }
                            await("Earpiece call did not enable proximity protection", ::proximityHeld)
                            main { service.speaker(incomingId) }
                            await("Speakerphone did not release proximity protection") { !proximityHeld() }
                            main { service.speaker(incomingId) }
                            await("Returning to the earpiece did not restore proximity protection", ::proximityHeld)
                        }
                        val originalAppearance = prefs.appearance
                        val connectedCore = main { (coreField.get(service) as Core).nativePointer }
                        try {
                            for (appearance in listOf(Appearance.DARK, Appearance.LIGHT, Appearance.SYSTEM)) {
                                main { activity.setAppearance(appearance) }
                                Thread.sleep(150)
                                main {
                                    check(activity.sip === service && (coreField.get(service) as Core).nativePointer == connectedCore)
                                    check(service.state.lines.any { it.id == incomingId && it.phase == Phase.ACTIVE }) {
                                        "Changing appearance disconnected the call"
                                    }
                                }
                            }
                        } finally {
                            main { activity.setAppearance(originalAppearance) }
                        }
                    }
                    if (index == 3) notification.actions.first { it.title.toString() == "Decline" }.actionIntent.send()
                    else main { service.end(incomingId) }
                    await("Declined call did not end") { main { service.state.lines.isEmpty() } }
                    check(!proximityHeld()) { "Ending the call did not release proximity protection" }
                    check(!playing()) { "Ringtone continued after declining the call" }
                    await("Incoming notification was not removed after the call") {
                        notificationManager.activeNotifications.none { it.id == 901 }
                    }
                    await("Vibration continued after declining the call") { !vibrationActive() }
                    await("Ending the call stopped background availability") {
                        activity.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 900 }
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        val serviceDump = ParcelFileDescriptor.AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand("dumpsys activity services org.sippy")
                        ).bufferedReader().use { it.readText() }
                        check("types=0x40000000" in serviceDump) { "Idle availability retained the microphone service type" }
                    }
                }
            }
        } finally {
            main {
                service.state.lines.forEach { service.end(it.id) }
                prefs.save(originalProfile)
                prefs.backgroundCalling = originalBackground
                prefs.saveOptions(originalOptions)
                prefs.data.edit().putBoolean("vibrateOnIncoming", originalVibrate).apply()
                service.configure()
                activity.finish()
            }
        }
    }
}
