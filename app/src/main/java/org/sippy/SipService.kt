package org.sippy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.LogLevel
import org.linphone.core.LoggingService
import org.linphone.core.LoggingServiceListenerStub
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.util.Locale

internal enum class Phase(val label: String) {
    INCOMING("Incoming call"), DIALING("Calling"), RINGING("Ringing"), ACTIVE("Connected"),
    HOLDING("Putting on hold"), HELD("On hold"), REMOTE_HOLD("Held by other party"),
    RESUMING("Resuming"), CONNECTING("Connecting")
}
internal data class Line(
    val id: Long, val name: String, val address: String, val phase: Phase,
    val seconds: Int, val muted: Boolean, val speaker: Boolean,
)
internal data class Consultation(val source: Long, val target: Long)
internal data class SipState(
    val status: String = "Starting SIP",
    val ready: Boolean = false,
    val lines: List<Line> = emptyList(),
    val selected: Long? = null,
    val consultation: Consultation? = null,
    val availableCodecs: Set<String> = emptySet(),
    val busy: Boolean = false,
    val registrationDetail: String? = null,
)

class SipService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@SipService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Preferences(this) }
    private val activityStore by lazy { ActivityStore.get(this) }
    private var sipLogger: LoggingService? = null
    @Volatile private var logSecret = ""
    private val logListener = object : LoggingServiceListenerStub() {
        override fun onLogMessageWritten(service: LoggingService, domain: String, level: LogLevel, message: String) {
            val wire = if (domain.contains("belle", ignoreCase = true)) sipLogMessage(message) else null
            when {
                wire != null -> activityStore.event(EventKind.SIP, wire, logSecret)
                level == LogLevel.Error || level == LogLevel.Fatal ->
                    activityStore.event(EventKind.ERROR, "$domain · ${message.lineSequence().firstOrNull().orEmpty()}", logSecret)
                level == LogLevel.Warning ->
                    activityStore.event(EventKind.WARNING, "$domain · ${message.lineSequence().firstOrNull().orEmpty()}", logSecret)
            }
        }
    }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var core: Core? = null
    private var factory: Factory? = null
    private var profile = SipProfile()
    private var selected: Long? = null
    private var consultation: Consultation? = null
    private var transferPending: Long? = null
    private var transferTimeout: Runnable? = null
    private var activeNotification = false
    private var microphoneForeground = false
    private var lastNotification: Triple<String, String, Boolean>? = null
    private var status = "Starting SIP"
    private var registrationDetail: String? = null
    private var ready = false
    private var availableCodecs = emptySet<String>()
    private var pending: AfterHold? = null
    private val incomingRingtone by lazy { IncomingRingtone(this) }

    private data class AfterHold(val line: Long, val action: () -> Unit, val timeout: Runnable)

    internal var state = SipState()
        private set
    internal var onChange: ((SipState) -> Unit)? = null
    internal var onMessage: ((String) -> Unit)? = null

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
            val failure = if (state == RegistrationState.Failed) {
                val error = account.errorInfo
                val code = error?.protocolCode?.takeIf { it in 300..699 }
                val phrase = error?.phrase?.takeIf { it.isNotBlank() } ?: message
                val reason = error?.reason?.name?.takeUnless { it == "None" }
                when {
                    code != null -> "SIP $code" + phrase.takeUnless { it.isBlank() || it.startsWith(code.toString()) }
                        ?.let { " · $it" }.orEmpty()
                    reason != null && phrase.isNotBlank() -> "$reason · $phrase"
                    else -> phrase.ifBlank { "Linphone did not report a SIP response code." }
                }
            } else null
            handler.post {
                if (this@SipService.core?.nativePointer != core.nativePointer || core.defaultAccount?.nativePointer != account.nativePointer) return@post
                if (failure != null || state == RegistrationState.Ok || state == RegistrationState.Cleared) {
                    registrationDetail = failure
                }
                ready = state == RegistrationState.Ok || state == RegistrationState.Refreshing || !profile.register
                status = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Registering…"
                    RegistrationState.Failed -> "Registration failed"
                    RegistrationState.Refreshing -> "Refreshing registration"
                    RegistrationState.Cleared -> "Registration cleared"
                    else -> if (profile.register) "Waiting to register" else "Direct SIP"
                }
                val category = if (state == RegistrationState.Failed) EventKind.ERROR else EventKind.INFO
                activityStore.event(category, listOfNotNull(status, failure).joinToString(" · "), logSecret)
                publish()
            }
        }

        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            recordCall(callLog)
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val id = call.nativePointer
            handler.post {
                if (this@SipService.core?.nativePointer != core.nativePointer) return@post
                if (state == Call.State.Error) report("Call failed · $message", EventKind.ERROR)
                else if (state in setOf(Call.State.IncomingReceived, Call.State.OutgoingInit, Call.State.Connected,
                        Call.State.Paused, Call.State.Resuming, Call.State.End)) {
                    activityStore.event(EventKind.INFO, "Call · $message", logSecret)
                }
                val action = pending
                if (action?.line == id && state in setOf(Call.State.Paused, Call.State.End, Call.State.Released, Call.State.Error)) {
                    clearPending()
                    action.action()
                }
                reconcile()
            }
        }

        override fun onTransferStateChanged(core: Core, transferred: Call, callState: Call.State) {
            val id = transferred.nativePointer
            handler.post {
                if (this@SipService.core?.nativePointer != core.nativePointer || transferPending != id) return@post
                when (callState) {
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        clearTransfer()
                        consultation = null
                        report("Transfer completed.")
                    }
                    Call.State.Error, Call.State.End -> {
                        clearTransfer()
                        report("Transfer failed. You can return to the calls.", EventKind.WARNING)
                    }
                    else -> Unit
                }
                reconcile()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activityStore.schedulePruning()
        activityStore.prune()
        notifications.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL, "Active SIP calls", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(AVAILABILITY_CHANNEL, "Background call availability", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(INCOMING_CHANNEL, "Incoming SIP calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        if (prefs.keepAvailable) promoteForeground(availabilityNotification(), microphone = false)
        configure()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == END_ACTION) {
            val id = intent.getLongExtra("line", 0)
            end(id)
        }
        if (!prefs.keepAvailable && calls().isEmpty()) stopSelf()
        return if (prefs.keepAvailable || calls().isNotEmpty()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        incomingRingtone.stop()
        notifications.cancel(INCOMING_ID)
        core?.let { engine -> engine.removeListener(listener); engine.stop() }
        sipLogger?.removeListener(logListener)
        sipLogger = null
        logSecret = ""
        super.onDestroy()
    }

    internal fun configure() {
        if (calls().isNotEmpty()) return
        core?.let { it.removeListener(listener); it.stop() }
        core = null
        var initialized: Core? = null
        ready = false
        status = "Set up your SIP account"
        registrationDetail = null
        runCatching {
            profile = prefs.profile()
            logSecret = profile.password
            val f = Factory.instance()
            factory = f
            if (sipLogger == null) {
                f.loggingService.also { logger ->
                    logger.setLogLevel(LogLevel.Message)
                    logger.addListener(logListener)
                    sipLogger = logger
                }
            }
            val engine = f.createCore(null, null, applicationContext)
            initialized = engine
            core = engine
            engine.setUserAgent("Sippy", "1.1.5")
            engine.setMaxCalls(4)
            engine.setKeepAliveEnabled(true)
            engine.setAutoIterateEnabled(true)
            engine.setVideoCaptureEnabled(false)
            engine.setVideoDisplayEnabled(false)
            engine.setEchoCancellationEnabled(profile.echoCancellation)
            engine.setIpv6Enabled(profile.ipv6)
            engine.setUseRfc2833ForDtmf(profile.dtmf == Dtmf.RTP)
            engine.setUseInfoForDtmf(profile.dtmf == Dtmf.INFO)
            val encryption = MediaEncryption.valueOf(profile.encryption.name)
            require(engine.setMediaEncryption(encryption) == 0) { "Media encryption is unavailable." }
            engine.setMediaEncryptionMandatory(encryption != MediaEncryption.None)
            val transports = engine.transports
            // -1 lets Linphone bind a random free port; 0 disables the transport.
            val port = profile.localPort.takeIf { it != 0 } ?: -1
            transports.setUdpPort(if (profile.transport == Transport.UDP) port else 0)
            transports.setTcpPort(if (profile.transport == Transport.TCP) port else 0)
            transports.setTlsPort(if (profile.transport == Transport.TLS) port else 0)
            require(engine.setTransports(transports) == 0) { "Cannot bind the local SIP port." }
            engine.setAudioPort(profile.rtpPort.takeIf { it > 0 } ?: -1)
            updateRingtone()
            engine.addListener(listener)
            require(engine.start() == 0) { "Could not start the SIP engine." }
            engine.callLogs.forEach(::recordCall)
            val payloads = engine.audioPayloadTypes
            availableCodecs = payloads.filter { it.isUsable }.map { it.mimeType.lowercase(Locale.ROOT) }.toSet()
            applyCodecs(requireEnabled = profile.configured)
            if (!profile.configured) return@runCatching

            profile.validate()
            val params = engine.createAccountParams()
            val identity = address("sip:${profile.username}@${profile.identityDomain}")
            if (profile.displayName.isNotBlank()) check(identity.setDisplayName(profile.displayName) == 0) {
                "Invalid SIP display name."
            }
            check(params.setIdentityAddress(identity) == 0) { "Invalid SIP identity." }
            val nextHop = profile.proxy.ifBlank { profile.registrar }
            // Linphone 5.4 uses the account server address as the REGISTER next hop.
            // The account identity still supplies the registration domain.
            check(params.setServerAddress(serverAddress(nextHop)) == 0) { "Invalid SIP server." }
            if (profile.proxy.isNotBlank()) {
                val route = serverAddress(nextHop, looseRoute = true)
                check(params.setRoutesAddresses(arrayOf(route)) == 0) { "Invalid outbound proxy." }
            }
            params.setExpires(profile.expiry)
            params.setRegisterEnabled(profile.register)
            if (profile.ice || profile.stun.isNotBlank()) {
                val nat = engine.createNatPolicy()
                nat.setIceEnabled(profile.ice)
                if (profile.stun.isNotBlank()) {
                    nat.setStunServer(profile.stun)
                    nat.setStunEnabled(true)
                }
                params.setNatPolicy(nat)
            }

            if (profile.password.isNotEmpty()) {
                engine.addAuthInfo(f.createAuthInfo(profile.username, profile.authUser.ifBlank { null },
                    profile.password, null, profile.realm.ifBlank { null }, null))
            }
            val account = engine.createAccount(params)
            status = if (profile.register) "Registering…" else "Direct SIP"
            activityStore.event(EventKind.INFO, "$status · ${profile.transport} · $nextHop")
            check(engine.addAccount(account) == 0) { "Could not add the SIP account." }
            engine.setDefaultAccount(account)
            ready = !profile.register
        }.onFailure {
            initialized?.let { engine -> runCatching { engine.removeListener(listener); engine.stop() } }
            core = null
            ready = false
            status = if (it is LinkageError) "SIP engine unavailable" else "SIP setup failed"
            registrationDetail = it.localizedMessage ?: "Unknown SIP setup error."
            activityStore.event(EventKind.ERROR, "$status · $registrationDetail", logSecret)
        }
        updateBackgroundAvailability()
    }

    internal fun updateBackgroundAvailability() {
        if (prefs.keepAvailable) {
            if (!activeNotification) promoteForeground(availabilityNotification(), microphone = false)
            // A started service survives the activity's final unbind and task dismissal.
            startService(Intent(this, SipService::class.java).setAction(KEEP_AVAILABLE))
        } else if (calls().isEmpty()) {
            removeForeground()
            stopSelf()
        }
        publish()
    }

    internal fun updateRingtone() {
        // Sippy owns both default and selected tones so device-dependent SDK ringing
        // cannot leave the default option silent or play a duplicate ringtone.
        core?.setNativeRingingEnabled(false)
        core?.setRing(null)
    }

    private fun recordCall(log: CallLog) {
        val incoming = log.dir == Call.Dir.Incoming
        val remote = log.remoteAddress
        val address = safeCallAddress(remote.asStringUriOnly())
        val time = log.startDate * 1000L
        val outcome = when (log.status) {
            Call.Status.Success -> CallOutcome.ANSWERED
            Call.Status.Missed -> if (incoming) CallOutcome.MISSED else CallOutcome.UNANSWERED
            Call.Status.Declined -> CallOutcome.DECLINED
            Call.Status.AcceptedElsewhere -> CallOutcome.ELSEWHERE
            Call.Status.DeclinedElsewhere -> CallOutcome.DECLINED_ELSEWHERE
            Call.Status.Aborted -> when {
                incoming -> CallOutcome.MISSED
                (log.errorInfo?.protocolCode ?: 0) >= 400 -> CallOutcome.FAILED
                else -> CallOutcome.UNANSWERED
            }
            else -> CallOutcome.FAILED
        }
        val name = remote.displayName ?: remote.username ?: "Unknown caller"
        val id = "${log.callId ?: address}:$time:$incoming"
        activityStore.record(HistoryRecord(id, time, incoming, outcome, name, address, log.duration))
    }

    internal fun applyCodecs(requireEnabled: Boolean = true) {
        val engine = core ?: return
        val payloads = engine.audioPayloadTypes
        val order = prefs.codecs()
        val priority = order.mapIndexed { index, codec -> codec.id to index }.toMap()
        val enabled = order.filter { it.enabled }.map { it.id }.toSet()
        fun id(mime: String) = mime.lowercase(Locale.ROOT)
        require(!requireEnabled || availableCodecs.any { it in enabled }) { "Enable at least one available codec." }
        val sorted = payloads.sortedBy { priority[id(it.mimeType)] ?: Int.MAX_VALUE }
        sorted.forEach { it.enable(id(it.mimeType) in enabled && it.isUsable) }
        engine.setAudioPayloadTypes(sorted.toTypedArray())
    }

    internal fun dial(raw: String, consultSource: Long? = null) = command {
        check(ready) { "Connect your SIP account before calling." }
        check(calls().size < 4) { "All four call lines are in use." }
        check(consultSource == null || consultation == null) { "Finish or cancel the current consultation first." }
        val engine = checkNotNull(core)
        val target = destination(raw)
        val source = consultSource?.let { id ->
            requireNotNull(find(id)) { "The original call has ended." }.also {
                check(it.state in setOf(Call.State.Paused, Call.State.StreamsRunning)) { "Wait for the original call to connect." }
            }
        }
        val current = calls().firstOrNull { it.state == Call.State.StreamsRunning }
        if (current == null) check(calls().all { phase(it.state) in setOf(Phase.HELD, Phase.REMOTE_HOLD) }) {
            "Wait for the other line to connect or finish."
        }
        val previous = current?.nativePointer
        afterHold(current) {
            command {
                var launched = false
                try {
                    ensureForeground()
                    val params = requireNotNull(engine.createCallParams(null)).apply { setVideoEnabled(false) }
                    val call = engine.inviteAddressWithParams(target, params) ?: error("Could not start the call.")
                    launched = true
                    selected = call.nativePointer
                    if (source != null && find(consultSource) != null) consultation = Consultation(consultSource, call.nativePointer)
                } finally {
                    if (!launched) previous?.let(::restoreParked)
                }
            }
        }
    }

    internal fun answer(id: Long) = command {
        val incoming = find(id) ?: error("The incoming call has ended.")
        check(phase(incoming.state) == Phase.INCOMING) { "This call is no longer ringing." }
        val other = calls().firstOrNull { it.nativePointer != id && it.state == Call.State.StreamsRunning }
        val previous = other?.nativePointer
        afterHold(other) {
            command {
                var answered = false
                try {
                    check(find(id) != null) { "The incoming call has ended." }
                    ensureForeground()
                    val params = requireNotNull(core?.createCallParams(incoming)).apply { setVideoEnabled(false) }
                    check(incoming.acceptWithParams(params) == 0) { "Could not answer the call." }
                    answered = true
                    selected = id
                } finally {
                    if (!answered) previous?.let(::restoreParked)
                }
            }
        }
    }

    internal fun end(id: Long) = command {
        val call = find(id) ?: return@command
        check(call.terminate() == 0) { "Could not end the call." }
    }

    internal fun hold(id: Long) = command {
        val call = find(id) ?: error("The call has ended.")
        check(pending == null && transferPending == null && call.state == Call.State.StreamsRunning &&
            call.pause() == 0) { "Could not hold this call." }
    }

    internal fun select(id: Long) = command {
        val target = find(id) ?: error("The call has ended.")
        if (target.state != Call.State.Paused) {
            selected = id
            return@command
        }
        val current = calls().firstOrNull { it.nativePointer != id && it.state == Call.State.StreamsRunning }
        val previous = current?.nativePointer
        if (current == null) check(calls().none { it.nativePointer != id &&
            phase(it.state) !in setOf(Phase.HELD, Phase.REMOTE_HOLD, Phase.INCOMING) }) {
            "Wait for the other line to connect or finish."
        }
        afterHold(current) {
            command {
                val resumed = find(id)?.resume() == 0
                if (!resumed) previous?.let(::restoreParked)
                check(resumed) { "Could not resume this call." }
                selected = id
            }
        }
    }

    internal fun digits(id: Long, digit: Char) = command {
        val call = find(id)
        check(digit in "0123456789*#" && call?.state == Call.State.StreamsRunning && call.sendDtmf(digit) == 0) {
            "DTMF is available on a connected call."
        }
    }

    internal fun mute(id: Long) = command {
        find(id)?.let { it.setMicrophoneMuted(!it.microphoneMuted) }
    }

    internal fun speaker(id: Long) = command {
        val call = find(id) ?: return@command
        val devices = core?.extendedAudioDevices.orEmpty()
        val loud = call.outputAudioDevice?.type == AudioDevice.Type.Speaker
        val device = if (loud) devices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
            ?: core?.defaultOutputAudioDevice else devices.firstOrNull { it.type == AudioDevice.Type.Speaker }
        call.setOutputAudioDevice(requireNotNull(device) { "This audio route is unavailable." })
    }

    internal fun blind(id: Long, raw: String) = command {
        check(transferPending == null && pending == null && consultation == null) { "Finish the current transfer first." }
        val call = find(id) ?: error("The call has ended.")
        check(call.state in setOf(Call.State.Paused, Call.State.StreamsRunning)) { "Wait for the call to connect." }
        val destination = destination(raw)
        trackTransfer(id)
        if (call.transferTo(destination) != 0) {
            clearTransfer()
            error("Could not start the transfer.")
        }
    }

    internal fun completeTransfer() = command {
        check(transferPending == null && pending == null) { "A call operation is already in progress." }
        val transfer = consultation ?: return@command
        val original = find(transfer.source) ?: error("The original call has ended.")
        val target = find(transfer.target) ?: error("The consultation call has ended.")
        val established = setOf(Call.State.Paused, Call.State.StreamsRunning)
        check(original.state in established && target.state in established) { "Wait for both calls to connect." }
        trackTransfer(transfer.source)
        if (original.transferToAnother(target) != 0) {
            clearTransfer()
            error("Could not complete the transfer.")
        }
    }

    internal fun cancelTransfer() = command {
        check(transferPending == null) { "The transfer is already in progress." }
        val transfer = consultation ?: return@command
        consultation = null
        find(transfer.target)?.terminate()
        restoreParked(transfer.source)
    }

    private fun restoreParked(id: Long) {
        val line = find(id) ?: return
        if (line.state == Call.State.Paused && calls().none { it.state == Call.State.StreamsRunning }) {
            if (line.resume() == 0) selected = id
        }
    }

    private fun afterHold(call: Call?, action: () -> Unit) {
        check(pending == null && transferPending == null) { "A call operation is already in progress." }
        if (call == null) {
            action()
            return
        }
        val id = call.nativePointer
        val timeout = Runnable {
            clearPending()
            restoreParked(id)
            report("The other line did not acknowledge hold.", EventKind.WARNING)
            reconcile()
        }
        pending = AfterHold(id, action, timeout)
        if (call.pause() != 0) {
            clearPending()
            error("Could not put the current line on hold.")
        }
        handler.postDelayed(timeout, 8000L)
    }

    private fun clearPending() {
        pending?.let { handler.removeCallbacks(it.timeout) }
        pending = null
    }

    private fun trackTransfer(id: Long) {
        transferPending = id
        transferTimeout = Runnable {
            clearTransfer()
            report("The SIP server did not confirm the transfer.", EventKind.WARNING)
            reconcile()
        }.also { handler.postDelayed(it, 35_000L) }
    }

    private fun clearTransfer() {
        transferTimeout?.let(handler::removeCallbacks)
        transferTimeout = null
        transferPending = null
    }

    private fun destination(raw: String): Address {
        val target = requireNotNull(dialTarget(raw)) { "Enter a number or SIP address." }
        if ('@' in target) return address(if (target.contains(':') && target.substringBefore(':').lowercase() in setOf("sip", "sips")) target else "sip:$target")
        val domain = if (profile.register) profile.identityDomain else profile.domain
        return address("sip:${target.replace("#", "%23")}@$domain")
    }

    private fun address(uri: String): Address =
        requireNotNull(factory?.createAddress(uri)) { "Invalid SIP address." }

    private fun serverAddress(host: String, looseRoute: Boolean = false): Address =
        address(if (looseRoute) "sip:$host;lr" else "sip:$host").apply {
            check(setTransport(when (profile.transport) {
                Transport.UDP -> TransportType.Udp
                Transport.TCP -> TransportType.Tcp
                Transport.TLS -> TransportType.Tls
            }) == 0) { "Invalid SIP transport." }
            check(!looseRoute || hasUriParam("lr")) { "Invalid outbound proxy route." }
        }

    private fun calls() = core?.calls.orEmpty().filter {
        it.state !in setOf(Call.State.End, Call.State.Error, Call.State.Released)
    }
    private fun find(id: Long) = calls().firstOrNull { it.nativePointer == id }

    private fun phase(state: Call.State) = when (state) {
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> Phase.INCOMING
        Call.State.OutgoingInit, Call.State.OutgoingProgress -> Phase.DIALING
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> Phase.RINGING
        Call.State.StreamsRunning -> Phase.ACTIVE
        Call.State.Pausing -> Phase.HOLDING
        Call.State.Paused -> Phase.HELD
        Call.State.PausedByRemote -> Phase.REMOTE_HOLD
        Call.State.Resuming -> Phase.RESUMING
        else -> Phase.CONNECTING
    }

    private fun reconcile() {
        val current = calls()
        consultation?.let { transfer ->
            if (current.none { it.nativePointer == transfer.target }) {
                consultation = null
                if (transferPending == null) restoreParked(transfer.source)
            } else if (current.none { it.nativePointer == transfer.source }) consultation = null
        }
        if (current.none { it.nativePointer == selected }) {
            selected = current.firstOrNull { it.state == Call.State.StreamsRunning }?.nativePointer
                ?: current.firstOrNull()?.nativePointer
        }
        if (current.isEmpty() && activeNotification) {
            lastNotification = null
            if (prefs.keepAvailable) {
                if (microphoneForeground) promoteForeground(availabilityNotification(), microphone = false)
            } else {
                removeForeground()
                stopSelf()
            }
        }
        publish()
    }

    private fun publish() {
        val lines = calls().map { call ->
            val address = call.remoteAddress
            val number = address.username ?: address.asStringUriOnly()
            Line(call.nativePointer, address.displayName ?: number, number, phase(call.state), call.duration,
                call.microphoneMuted, call.outputAudioDevice?.type == AudioDevice.Type.Speaker)
        }
        state = SipState(status, ready, lines, selected, consultation, availableCodecs,
            pending != null || transferPending != null, registrationDetail)
        if (activeNotification) {
            val line = if (microphoneForeground) lines.firstOrNull { it.id == selected } ?: lines.firstOrNull() else null
            val signature = Triple(line?.name ?: "Call availability", line?.phase?.label ?: status, line != null)
            if (lastNotification != signature || line?.id != notifiedLine) {
                lastNotification = signature
                notifiedLine = line?.id
                notifications.notify(ACTIVE_ID, if (line != null) notification(line, false) else availabilityNotification())
            }
        }
        val ringing = lines.firstOrNull { it.phase == Phase.INCOMING }
        if (ringing?.id != incomingLine) {
            incomingLine = ringing?.id
            incomingRingtone.stop()
            if (ringing != null) incomingRingtone.start(
                prefs.data.getString("ringtoneUri", null)?.let(Uri::parse), prefs.vibrateOnIncoming)
            if (ringing == null) notifications.cancel(INCOMING_ID)
            else notifications.notify(INCOMING_ID, notification(ringing, true))
        }
        onChange?.invoke(state)
    }

    private var incomingLine: Long? = null
    private var notifiedLine: Long? = null

    private fun notification(line: Line?, incoming: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).setAction(OPEN_CALL)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, if (incoming) INCOMING_CHANNEL else ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(if (incoming) "Incoming call · ${line?.name}" else "Sippy · ${line?.name ?: "Starting call"}")
            .setContentText(if (incoming) "Tap to answer or decline" else "${line?.phase?.label ?: "Connecting"} · Tap to open Sippy")
            .setContentIntent(pendingOpen)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (line != null) {
            if (incoming) {
                val accept = Intent(this, MainActivity::class.java).setAction(ACCEPT_CALL)
                    .putExtra("line", line.id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingAccept = PendingIntent.getActivity(this, 3, accept,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(null, "Accept", pendingAccept).build())
            }
            val end = Intent(this, SipService::class.java).setAction(END_ACTION).putExtra("line", line.id)
            val pendingEnd = PendingIntent.getService(this, if (incoming) 1 else 2, end,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, if (incoming) "Decline" else "End call", pendingEnd).build())
        }
        return builder.build()
    }

    private fun ensureForeground() {
        if (microphoneForeground) return
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required."
        }
        startService(Intent(this, SipService::class.java))
        try {
            promoteForeground(notification(null, false), microphone = true)
        } catch (error: Exception) {
            if (!activeNotification) stopSelf()
            throw error
        }
    }

    private fun promoteForeground(notification: Notification, microphone: Boolean) {
        val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (microphone && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ACTIVE_ID, notification, type)
        else startForeground(ACTIVE_ID, notification)
        activeNotification = true
        microphoneForeground = microphone
    }

    private fun removeForeground() {
        if (activeNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        activeNotification = false
        microphoneForeground = false
        lastNotification = null
    }

    private fun availabilityNotification(): Notification {
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, AVAILABILITY_CHANNEL)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle("Sippy · Incoming calls enabled")
            .setContentText("$status · Tap to open Sippy")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE).build()
    }

    private inline fun command(action: () -> Unit) {
        runCatching(action).onFailure { report(it.localizedMessage ?: "The SIP operation failed.", EventKind.ERROR) }
        reconcile()
    }
    private fun report(message: String, kind: EventKind = EventKind.INFO) {
        activityStore.event(kind, message, logSecret)
        onMessage?.invoke(message)
    }

    companion object {
        const val OPEN_CALL = "org.sippy.OPEN_CALL"
        const val ACCEPT_CALL = "org.sippy.ACCEPT_CALL"
        const val KEEP_AVAILABLE = "org.sippy.KEEP_AVAILABLE"
        private const val END_ACTION = "org.sippy.END_CALL"
        private const val ACTIVE_CHANNEL = "sippy.active"
        private const val AVAILABILITY_CHANNEL = "sippy.availability"
        private const val INCOMING_CHANNEL = "sippy.incoming.v2"
        private const val ACTIVE_ID = 900
        private const val INCOMING_ID = 901
    }
}
