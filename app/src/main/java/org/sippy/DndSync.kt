package org.sippy

import android.os.Handler
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.Event
import org.linphone.core.MediaDirection
import org.linphone.core.PublishState
import org.linphone.core.SubscriptionState

/** SIP transactions use Linphone's registered accounts, routing, authentication and TLS. */
internal class DndSync(
    private val core: Core,
    private val handler: Handler,
    private val state: (String) -> Boolean,
    private val isReady: (String) -> Boolean,
    private val hasCalls: () -> Boolean,
    private val destination: (SipAccountConfig, String) -> Address,
    private val remoteState: (String, Boolean) -> Boolean,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
) {
    private class Session(val config: SipAccountConfig, val account: Account) {
        var started = false
        var sent: Boolean? = null
        var confirmed: Boolean? = null
        var publishing = false
        var pendingPublish: Boolean? = null
        var subscriptionActive = false
        var pendingFeature: Boolean? = null
        var requestedFeature: Boolean? = null
        var confirmationTimeout: Runnable? = null
        var event: Event? = null
        var pendingFac: Boolean? = null
        var status = "Not synchronized"
        var refresh: Runnable? = null
        var facCall: Call? = null
        var timeout: Runnable? = null
        var retireTimeout: Runnable? = null
    }
    private val sessions = mutableMapOf<String, Session>()
    private val retiring = mutableListOf<Session>()
    private val technicalCalls = mutableSetOf<Long>()
    private val technicalLogs = mutableSetOf<String>()
    private var closed = false
    private val notifyOwners = mutableMapOf<Long, Session>()
    private val outgoingNotifies = mutableMapOf<Long, Event>()

    fun status(id: String): String = if (retiring.any { it.config.id == id }) "Server reset pending until calls end" else sessions[id]?.status ?: "Disabled"
    fun internalCall(call: Call) = call.nativePointer in technicalCalls
    fun internalLog(call: CallLog) = call.refKey == "sippy-dnd-fac" || call.callId in technicalLogs

    fun update(accounts: List<SipAccountConfig>, native: Map<String, Account>) {
        val desired = accounts.associateBy { it.id }
        sessions.toMap().forEach { (id, old) ->
            val replacement = desired[id]
            if (replacement?.dnd != old.config.dnd || native[id]?.nativePointer != old.account.nativePointer) {
                // Clear a previous published busy state before withdrawing/changing its configuration.
                val clearOld = (old.sent == true || old.confirmed == true) && old.config.dnd.show
                if (clearOld && old.config.dnd.serverSync == DndServerSync.FAC && old.config.dnd.facMethod == DndFacMethod.INVITE && hasCalls()) {
                    old.pendingFac = false
                    retiring.add(old)
                } else {
                    if (clearOld) {
                        send(old, false)
                        retiring.add(old)
                        old.retireTimeout = Runnable { stop(old); retiring.remove(old); changed() }
                            .also { handler.postDelayed(it, 16000) }
                    } else stop(old)
                }
                sessions.remove(id)
            }
        }
        accounts.forEach { config ->
            val account = native[config.id] ?: return@forEach
            val session = sessions.getOrPut(config.id) { Session(config, account) }
            if (!config.dnd.show || config.dnd.serverSync == DndServerSync.DISABLED) session.status = "Disabled"
            else if (isReady(config.id)) ready(config.id)
            else session.status = "Waiting for account registration"
        }
    }
    fun ready(id: String) {
        val s = sessions[id] ?: return
        if (closed || s.started || !s.config.dnd.show || s.config.dnd.serverSync == DndServerSync.DISABLED) return
        s.started = true
        if (s.config.dnd.serverSync == DndServerSync.AS_FEATURE_EVENT) attempt(s) {
            s.event = withAccount(s) { core.createSubscribe(s.account.params.identityAddress!!, "as-feature-event", 600) }
            check(s.event!!.sendSubscribe(null) == 0)
            s.status = "Waiting for server DND state"
            schedule(s)
        } else send(s, state(id))
    }
    fun localStateChanged(id: String) {
        val s = sessions[id] ?: return
        if (closed || !s.config.dnd.show || s.config.dnd.serverSync == DndServerSync.DISABLED) return
        if (!isReady(id)) { s.status = "Waiting for account registration"; changed(); return }
        if (!s.started) {
            ready(id)
            if (s.config.dnd.serverSync != DndServerSync.AS_FEATURE_EVENT) return
        }
        send(s, state(id))
    }
    fun idle() {
        if (hasCalls()) return
        (sessions.values.toList() + retiring.toList()).forEach { s -> s.pendingFac?.let { value -> s.pendingFac = null; facInvite(s, value) } }
    }
    fun beforeUserCall() {
        sessions.values.forEach { s ->
            s.facCall?.let { call ->
                s.pendingFac = state(s.config.id)
                call.terminate()
                s.facCall = null
                s.status = "FAC sync will retry after the call"
            }
        }
    }
    private inline fun <T> withAccount(s: Session, action: () -> T): T {
        val previous = core.defaultAccount
        core.setDefaultAccount(s.account)
        try { return action() } finally { core.setDefaultAccount(previous) }
    }
    private fun content(subtype: String, xml: String): Content = core.createContent().apply {
        setType("application"); setSubtype(subtype); setStringBuffer(xml)
    }
    private fun send(s: Session, enabled: Boolean) {
        if (closed) return
        if (s.config.dnd.serverSync == DndServerSync.PUBLISH && s.publishing) {
            s.pendingPublish = enabled; s.status = "Presence synchronization pending"; changed(); return
        }
        if (s.sent == enabled && s.event != null && s.config.dnd.serverSync == DndServerSync.PUBLISH) return
        attempt(s) {
            s.sent = enabled
            s.status = "Sending DND state"
            when (s.config.dnd.serverSync) {
                DndServerSync.DISABLED -> s.status = "Disabled"
                DndServerSync.PUBLISH -> {
                    val identity = s.account.params.identityAddress!!
                    val event = s.event ?: withAccount(s) { core.createPublish(identity, "presence", 600) }.also { s.event = it }
                    s.publishing = true
                    check(event.sendPublish(content("pidf+xml", DndXml.presence(identity.asStringUriOnly(), enabled))) == 0)
                    schedule(s)
                }
                DndServerSync.AS_FEATURE_EVENT -> {
                    val identity = s.account.params.identityAddress!!
                    s.requestedFeature = enabled
                    s.confirmationTimeout?.let(handler::removeCallbacks)
                    s.confirmationTimeout = Runnable {
                        if (s.requestedFeature != null) {
                            s.requestedFeature = null
                            s.status = "Server has not confirmed DND; local setting kept"
                            changed()
                        }
                    }.also { handler.postDelayed(it, 30000) }
                    if (s.config.dnd.featureSchema == DndFeatureSchema.THREE_CX) {
                        val event = withAccount(s) { core.createNotify(identity, "as-feature-event") }
                        notifyOwners[event.nativePointer] = s
                        outgoingNotifies[event.nativePointer] = event
                        check(event.notify(content("x-as-feature-event+xml", DndXml.featureEvent(identity.asStringUriOnly(), enabled))) == 0)
                    } else {
                        if (!s.subscriptionActive) {
                            s.pendingFeature = enabled
                            s.status = "Waiting for feature event subscription"
                            return@attempt
                        }
                        val event = s.event ?: error("Feature event subscription is unavailable")
                        s.subscriptionActive = false
                        check(event.sendSubscribe(content("x-as-feature-event+xml", DndXml.setFeature(identity.asStringUriOnly(), enabled, s.config.dnd.featureSchema))) == 0)
                    }
                }
                DndServerSync.FAC -> if (s.config.dnd.facMethod == DndFacMethod.MESSAGE) facMessage(s, enabled) else {
                    if (hasCalls() || s.facCall != null) {
                        s.pendingFac = enabled
                        s.status = "FAC sync pending until calls end"
                    } else facInvite(s, enabled)
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun facMessage(s: Session, enabled: Boolean) {
        val target = destination(s.config, if (enabled) s.config.dnd.facOn else s.config.dnd.facOff)
        val room = requireNotNull(withAccount(s) { core.getChatRoom(target, s.account.params.identityAddress!!) })
        val message = room.createMessageFromUtf8(if (enabled) "DND on" else "DND off")
        message.addListener(object : ChatMessageListenerStub() {
            override fun onMsgStateChanged(message: ChatMessage, state: ChatMessage.State) {
                handler.post {
                    if (closed || sessions[s.config.id] !== s) return@post
                    when (state) {
                        ChatMessage.State.Delivered -> s.status = "FAC MESSAGE accepted by server"
                        ChatMessage.State.NotDelivered -> failure(s, "FAC MESSAGE failed (SIP ${message.errorInfo?.protocolCode ?: 0})")
                        else -> return@post
                    }
                    changed()
                }
            }
        })
        message.send()
    }
    private fun facInvite(s: Session, enabled: Boolean) = attempt(s) {
        val target = destination(s.config, if (enabled) s.config.dnd.facOn else s.config.dnd.facOff)
        val params = requireNotNull(core.createCallParams(null)).apply {
            setAccount(s.account); setVideoEnabled(false); setAudioDirection(MediaDirection.Inactive)
        }
        CallEncryption.apply(core, params, s.config.profile.encryption)
        val call = requireNotNull(core.inviteAddressWithParams(target, params)) { "FAC INVITE could not be started" }
        technicalCalls.add(call.nativePointer)
        s.facCall = call
        try {
            call.callLog.setRefKey("sippy-dnd-fac")
            call.callLog.callId?.let { technicalLogs.add(it) }
            call.setMicrophoneMuted(true)
        } catch (failure: Exception) {
            call.terminate(); s.facCall = null
            throw failure
        }
        s.status = "Sending silent FAC INVITE"
        s.timeout = Runnable {
            if (s.facCall?.nativePointer == call.nativePointer) {
                call.terminate(); s.facCall = null; failure(s, "FAC INVITE timed out")
                if (s in retiring) { stop(s); retiring.remove(s) }
                changed()
            }
        }.also { handler.postDelayed(it, 15000) }
    }
    fun callState(call: Call, state: Call.State): Boolean {
        if (!internalCall(call)) return false
        val s = (sessions.values.toList() + retiring.toList()).firstOrNull { it.facCall?.nativePointer == call.nativePointer }
        if (s != null) when (state) {
            Call.State.Connected, Call.State.StreamsRunning -> {
                s.status = "FAC INVITE accepted by server"; call.terminate()
            }
            Call.State.Error -> failure(s, "FAC INVITE failed (SIP ${call.errorInfo?.protocolCode ?: 0})")
            else -> Unit
        }
        if (state in setOf(Call.State.End, Call.State.Error, Call.State.Released)) {
            s?.timeout?.let(handler::removeCallbacks)
            s?.facCall = null
            if (s != null && s in retiring) { stop(s); retiring.remove(s) }
            if (state == Call.State.Released) technicalCalls.remove(call.nativePointer)
        }
        changed()
        return true
    }
    fun publishState(event: Event, state: PublishState) {
        val s = (sessions.values.toList() + retiring.toList()).firstOrNull { it.event?.nativePointer == event.nativePointer } ?: return
        if (state in setOf(PublishState.Ok, PublishState.Error)) s.publishing = false
        when (state) {
            PublishState.Ok -> {
                s.status = "Presence synchronized"
                if (s in retiring && s.pendingPublish == null) { stop(s); retiring.remove(s) }
            }
            PublishState.Error -> { s.sent = null; failure(s, "Presence PUBLISH failed (SIP ${event.errorInfo.protocolCode})") }
            else -> return
        }
        val pending = s.pendingPublish
        s.pendingPublish = null
        if (pending != null) send(s, pending)
        changed()
    }
    fun subscriptionState(event: Event, state: SubscriptionState) {
        val s = (sessions.values.toList() + retiring.toList()).firstOrNull { it.event?.nativePointer == event.nativePointer } ?: return
        if (state == SubscriptionState.Active) {
            s.subscriptionActive = true
            val pending = s.pendingFeature
            s.pendingFeature = null
            if (pending != null) send(s, pending)
        }
        if (state == SubscriptionState.Error || state == SubscriptionState.Terminated) {
            s.subscriptionActive = false
            s.requestedFeature = null
            failure(s, "Feature event subscription ended (SIP ${event.errorInfo.protocolCode})")
            changed()
        }
    }
    fun notifySent(event: Event) {
        val s = notifyOwners.remove(event.nativePointer) ?: return
        outgoingNotifies.remove(event.nativePointer)
        val code = event.errorInfo.protocolCode
        if (code >= 300 || event.errorInfo.reason != org.linphone.core.Reason.None) failure(s, "Feature NOTIFY failed (SIP $code)") else s.status = "Feature NOTIFY sent to server"
        if (s in retiring) { stop(s); retiring.remove(s) }
        changed()
    }
    fun notify(event: Event, name: String, body: Content?) {
        if (!name.substringBefore(';').equals("as-feature-event", true) || body == null) return
        val s = (sessions.values.toList() + retiring.toList()).firstOrNull { it.event?.nativePointer == event.nativePointer && it.config.dnd.serverSync == DndServerSync.AS_FEATURE_EVENT } ?: return
        var partsSeen = 0
        fun state(part: Content, depth: Int = 0): Boolean? {
            if (depth > 4 || ++partsSeen > 32) return null
            if (part.isMultipart) return part.parts.take(16).firstNotNullOfOrNull { state(it, depth + 1) }
            if (!part.type.equals("application", true) || !part.subtype.equals("x-as-feature-event+xml", true)) return null
            return DndXml.featureState(part.stringBuffer ?: "")
        }
        val value = state(body) ?: return
        s.confirmed = value
        if (s in retiring) { if (!value) { stop(s); retiring.remove(s); changed() }; return }
        if (s.requestedFeature != null && s.requestedFeature != value) {
            s.status = "Waiting for server DND confirmation"; changed(); return
        }
        s.requestedFeature = null
        s.confirmationTimeout?.let(handler::removeCallbacks)
        s.status = if (remoteState(s.config.id, value)) "DND synchronized with server" else "Server DND differs from managed settings"
        changed()
    }
    private fun schedule(s: Session) {
        s.refresh?.let(handler::removeCallbacks)
        s.refresh = Runnable {
            if (closed || sessions[s.config.id] !== s) return@Runnable
            attempt(s) {
                if (s.config.dnd.serverSync == DndServerSync.PUBLISH) {
                    s.sent = null
                    send(s, state(s.config.id))
                } else check(s.event?.sendSubscribe(null) == 0)
            }
            schedule(s)
        }.also { handler.postDelayed(it, 300000) }
    }
    private fun failure(s: Session, message: String) { s.status = message; log("${s.config.name} · $message") }
    private inline fun attempt(s: Session, action: () -> Unit) {
        runCatching(action).onFailure { failure(s, "DND server synchronization failed") }
        changed()
    }
    private fun stop(s: Session) {
        s.refresh?.let(handler::removeCallbacks); s.timeout?.let(handler::removeCallbacks); s.retireTimeout?.let(handler::removeCallbacks); s.confirmationTimeout?.let(handler::removeCallbacks)
        s.event?.terminate(); s.facCall?.terminate()
    }
    fun close() {
        closed = true
        (sessions.values.toList() + retiring.toList()).forEach(::stop); sessions.clear(); retiring.clear()
        outgoingNotifies.values.forEach { it.terminate() }; outgoingNotifies.clear(); notifyOwners.clear()
        technicalCalls.clear()
    }
}
