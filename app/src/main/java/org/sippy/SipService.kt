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
import org.linphone.core.Content
import org.linphone.core.Event
import org.linphone.core.PublishState
import org.linphone.core.SubscriptionState
import org.linphone.core.Reason
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
    val seconds: Int, val muted: Boolean, val speaker: Boolean, val accountName: String = "",
)
internal data class AccountStatus(val id: String, val name: String, val status: String, val ready: Boolean, val detail: String? = null)
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
    val accounts: List<AccountStatus> = emptyList(),
    val outgoingAccountId: String = "",
    val outgoingAccountName: String = "",
    val dndAccounts: Set<String> = emptySet(),
)

class SipService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@SipService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Preferences(this) }
    private val activityStore by lazy { ActivityStore.get(this) }
    private var sipLogger: LoggingService? = null
    @Volatile private var logSecret: List<String> = emptyList()
    @Volatile private var factoryResetting = false
    private val logListener = object : LoggingServiceListenerStub() {
        override fun onLogMessageWritten(service: LoggingService, domain: String, level: LogLevel, message: String) {
            if (factoryResetting) return
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
    private var dndSync: DndSync? = null
    private var managedDndOverride: Boolean? = null
    private var factory: Factory? = null
    private var profile = SipProfile()
    private var appliedAccounts = emptyList<SipAccountConfig>()
    private val nativeAccounts = mutableMapOf<String, Account>()
    private val registrations = mutableMapOf<String, AccountStatus>()
    private val callEncryptions = mutableMapOf<Long, Encryption>()
    private fun selectedConfig() = appliedAccounts.firstOrNull { it.id == prefs.selectedAccount().id }
    private fun selectedName() = selectedConfig()?.name ?: prefs.selectedAccount().name
    private fun accountFor(call: Call): SipAccountConfig? {
        val native = call.params.account?.nativePointer
        return appliedAccounts.firstOrNull { nativeAccounts[it.id]?.nativePointer == native }
    }
    private fun selectOutgoing() {
        val selected = selectedConfig()
        profile = selected?.profile ?: prefs.profile()
        core?.setDefaultAccount(selected?.let { nativeAccounts[it.id] })
        val registration = selected?.let { registrations[it.id] }
        ready = registration?.ready == true
        status = registration?.status ?: "Set up your SIP account"
        registrationDetail = registration?.detail
    }
    internal fun transferMode(id: Long): TransferMode = find(id)?.let(::accountFor)?.let { config ->
        prefs.accounts().firstOrNull { it.id == config.id }?.options?.transferMode ?: config.options.transferMode
    } ?: prefs.transferMode

    internal fun selectAccount(id: String) {
        if (managedUpdatePending && appliedAccounts.none { it.id == id }) return report("This account will be available after all calls end.")
        prefs.selectAccount(id)
        selectOutgoing()
        publish()
    }
    internal fun dndSyncStatus(id: String): String = if (appliedAccounts.any { it.id == id && !it.profile.configured }) "Complete the SIP account first" else dndSync?.status(id) ?: "Disabled"
    internal fun updateDndBehaviour() {
        val latest = prefs.accounts().associateBy { it.id }
        appliedAccounts = appliedAccounts.map { old -> latest[old.id]?.let { old.copy(dnd = it.dnd, options = it.options) } ?: old }
        dndSync?.update(appliedAccounts, nativeAccounts)
        val forced = ManagedConfiguration.values["doNotDisturb"] as? Boolean
        if (forced != managedDndOverride) {
            managedDndOverride = forced
            appliedAccounts.forEach { dndSync?.localStateChanged(it.id) }
        }
        enforceDoNotDisturb()
        publish()
    }

    internal fun setDoNotDisturb(enabled: Boolean) {
        val id = prefs.selectedAccount().id
        if (prefs.setDndEnabled(id, enabled)) dndSync?.localStateChanged(id)
        enforceDoNotDisturb()
        publish()
    }
    private fun dndNames() = appliedAccounts.filter { prefs.dndEnabled(it.id) }.map { it.name }
    private fun dndLabel() = dndNames().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "DND: ").orEmpty()
    private fun shouldDecline(call: Call): Boolean {
        val config = accountFor(call) ?: selectedConfig() ?: return false
        return config.dnd.declines(prefs.dndEnabled(config.id), call.remoteParams?.getCustomHeader("Priority"))
    }
    private fun declineForDnd(call: Call, settings: DndSettings) {
        val f = factory ?: return
        val error = f.createErrorInfo().apply { set("SIP", Reason.Declined, 603, "Decline", null) }
        if (settings.reasonHeader) error.setSubErrorInfo(f.createErrorInfo().apply {
            set("SIP", Reason.Declined, 603, "Do Not Disturb", null)
        })
        call.declineWithErrorInfo(error)
    }
    private fun enforceDoNotDisturb() {
        calls().filter { phase(it.state) == Phase.INCOMING && shouldDecline(it) }.forEach { call ->
            declineForDnd(call, (accountFor(call) ?: selectedConfig())?.dnd ?: DndSettings())
        }
    }
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
    private val callProximity by lazy { CallProximity(this) }

    private data class AfterHold(val line: Long, val action: () -> Unit, val timeout: Runnable)

    internal var state = SipState()
        private set
    internal var onChange: ((SipState) -> Unit)? = null
    internal var onMessage: ((String) -> Unit)? = null

    internal var managedUpdatePending = false
        private set
    private var appliedCodecs = emptyList<Codec>()
    private var managedBlocked = false
    private val managedChanges: () -> Unit = { applyManagedConfiguration() }
    internal fun applyManagedConfiguration() {
        if (factoryResetting) return
        val reconnect = prefs.accounts().map { it.copy(dnd = DndSettings(), options = AccountOptions()) } != appliedAccounts.map { it.copy(dnd = DndSettings(), options = AccountOptions()) } ||
            ManagedConfiguration.blocksConnection != managedBlocked || core == null && !managedBlocked
        if (reconnect) {
            if (calls().isEmpty()) configure() else managedUpdatePending = true
        }
        if (!reconnect) selectOutgoing()
        updateDndBehaviour()
        enforceDoNotDisturb()
        updateIncomingVibration()
        updateBackgroundAvailability()
    }

    private val listener = object : CoreListenerStub() {
        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) {
            handler.post {
                if (this@SipService.core?.nativePointer == core.nativePointer) publish()
            }
        }

        override fun onAudioDevicesListUpdated(core: Core) {
            handler.post {
                if (this@SipService.core?.nativePointer == core.nativePointer) publish()
            }
        }
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
                if (this@SipService.core?.nativePointer != core.nativePointer) return@post
                val configured = appliedAccounts.firstOrNull { nativeAccounts[it.id]?.nativePointer == account.nativePointer } ?: return@post
                val registeredProfile = configured.profile
                val accountReady = state == RegistrationState.Ok || state == RegistrationState.Refreshing || !registeredProfile.register
                val accountStatus = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Registering…"
                    RegistrationState.Failed -> "Registration failed"
                    RegistrationState.Refreshing -> "Refreshing registration"
                    RegistrationState.Cleared -> "Registration cleared"
                    else -> if (registeredProfile.register) "Waiting to register" else "Direct SIP"
                }
                registrations[configured.id] = AccountStatus(configured.id, configured.name, accountStatus, accountReady, failure)
                selectOutgoing()
                if (accountReady) dndSync?.ready(configured.id)
                val category = if (state == RegistrationState.Failed) EventKind.ERROR else EventKind.INFO
                activityStore.event(category, listOfNotNull(configured.name, accountStatus, failure).joinToString(" · "), logSecret)
                publish()
            }
        }

        override fun onNotifyReceived(core: Core, linphoneEvent: Event, notifiedEvent: String, body: Content?) {
            handler.post { if (this@SipService.core?.nativePointer == core.nativePointer) dndSync?.notify(linphoneEvent, notifiedEvent, body) }
        }
        override fun onNotifySent(core: Core, linphoneEvent: Event, body: Content?) {
            handler.post { if (this@SipService.core?.nativePointer == core.nativePointer) dndSync?.notifySent(linphoneEvent) }
        }
        override fun onPublishStateChanged(core: Core, linphoneEvent: Event, state: PublishState) {
            handler.post { if (this@SipService.core?.nativePointer == core.nativePointer) dndSync?.publishState(linphoneEvent, state) }
        }
        override fun onSubscriptionStateChanged(core: Core, linphoneEvent: Event, state: SubscriptionState) {
            handler.post { if (this@SipService.core?.nativePointer == core.nativePointer) dndSync?.subscriptionState(linphoneEvent, state) }
        }
        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            recordCall(callLog)
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val id = call.nativePointer
            // Synchronous guard: reject an SDK fallback before it can establish clear audio.
            if (state !in setOf(Call.State.End, Call.State.Error, Call.State.Released)) {
                val required = callEncryptions.getOrPut(id) { accountFor(call)?.profile?.encryption ?: Encryption.None }
                val requested = MediaEncryption.valueOf(required.name)
                val wrongOffer = required in setOf(Encryption.SRTP, Encryption.DTLS) && state == Call.State.IncomingReceived &&
                    call.remoteParams?.mediaEncryption != requested
                val wrongLocal = required != Encryption.None && state in setOf(Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.Connected, Call.State.StreamsRunning) &&
                    call.params.mediaEncryption != requested
                if (wrongOffer || wrongLocal) {
                    call.setMicrophoneMuted(true); call.setSpeakerMuted(true)
                    if (state == Call.State.IncomingReceived) call.decline(Reason.NotAcceptable)
                    else handler.post {
                        // Do not destroy the SIP operation while the SDK is rebuilding
                        // it for a retry. The per-call mandatory policy already blocks
                        // clear media; stop that retry once its native callback returns.
                        call.terminate()
                        reconcile()
                    }
                    activityStore.event(EventKind.ERROR, "Call rejected: the account's required media encryption could not be negotiated.")
                    return
                }
            }
            if (state == Call.State.UpdatedByRemote) call.deferUpdate()
            handler.post {
                if (this@SipService.core?.nativePointer != core.nativePointer) return@post
                // currentParams is finalized after the synchronous native state callback.
                val required = callEncryptions[id]
                if (state == Call.State.StreamsRunning && call.state == Call.State.StreamsRunning &&
                    required in setOf(Encryption.SRTP, Encryption.DTLS) &&
                    call.currentParams.mediaEncryption != MediaEncryption.valueOf(checkNotNull(required).name)) {
                    call.setMicrophoneMuted(true); call.setSpeakerMuted(true); call.terminate()
                    report("Call rejected: negotiated media encryption does not match the account.", EventKind.ERROR)
                    reconcile()
                    return@post
                }
                if (state == Call.State.UpdatedByRemote) {
                    runCatching {
                        val params = requireNotNull(core.createCallParams(call))
                        CallEncryption.apply(core, params, callEncryptions[id] ?: Encryption.None)
                        withAccountCodecs(accountFor(call)?.id) { call.acceptUpdate(params) }
                    }.onFailure { call.terminate(); report("Could not preserve the account's media settings.", EventKind.ERROR) }
                }
                if (state == Call.State.Released) callEncryptions.remove(id)
                if (dndSync?.callState(call, state) == true) { reconcile(); return@post }
                val receiving = accountFor(call)
                if (managedUpdatePending && receiving?.managed == true && state in setOf(Call.State.IncomingReceived, Call.State.IncomingEarlyMedia) &&
                    prefs.accounts().none { it.id == receiving.id && SipAccountFields.identity(it.profile) == SipAccountFields.identity(receiving.profile) }) {
                    call.decline(Reason.Declined)
                    activityStore.event(EventKind.INFO, "Incoming call declined · managed account removed or replaced")
                    reconcile()
                    return@post
                }
                if (shouldDecline(call) && state in setOf(Call.State.IncomingReceived, Call.State.IncomingEarlyMedia)) {
                    declineForDnd(call, (accountFor(call) ?: selectedConfig())?.dnd ?: DndSettings())
                    activityStore.event(EventKind.INFO, "Incoming call declined · Do Not Disturb", logSecret)
                    reconcile()
                    return@post
                }
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
        ManagedConfiguration.observe(managedChanges)
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
        ManagedConfiguration.removeObserver(managedChanges)
        handler.removeCallbacksAndMessages(null)
        callProximity.release()
        incomingRingtone.stop()
        notifications.cancel(INCOMING_ID)
        core?.let { engine -> engine.removeListener(listener); engine.stop() }
        sipLogger?.removeListener(logListener)
        sipLogger = null
        logSecret = emptyList()
        super.onDestroy()
    }

    internal fun prepareFactoryReset() {
        check(calls().isEmpty()) { "End all calls before resetting Sippy." }
        factoryResetting = true
        try {
            incomingRingtone.stop()
            dndSync?.close(); dndSync = null
            core?.let { engine ->
                engine.removeListener(listener)
                engine.clearCallLogs()
                engine.clearAccounts()
                engine.clearAllAuthInfo()
                engine.stop()
            }
            core = null
            handler.removeCallbacksAndMessages(null)
            nativeAccounts.clear(); registrations.clear()
            sipLogger?.removeListener(logListener); sipLogger = null
            logSecret = emptyList()
        } catch (failure: Exception) { factoryResetting = false; throw failure }
    }

    internal fun finishFactoryReset() { factoryResetting = false; configure() }

    internal fun configure() {
        if (factoryResetting) return
        if (calls().isNotEmpty()) return
        core?.let { it.removeListener(listener) }
        dndSync?.close(); dndSync = null
        core?.stop()
        core = null
        nativeAccounts.clear()
        callEncryptions.clear()
        registrations.clear()
        managedUpdatePending = false
        managedBlocked = ManagedConfiguration.blocksConnection
        if (managedBlocked) {
            ready = false
            status = "Managed configuration needs attention"
            updateBackgroundAvailability()
            return
        }
        var initialized: Core? = null
        ready = false
        status = "Set up your SIP account"
        registrationDetail = null
        runCatching {
            appliedAccounts = prefs.accounts()
            require(appliedAccounts.filter { it.profile.configured }.groupBy { SipAccountFields.identity(it.profile) }.values.none {
                entries -> entries.map { SipAccountFields.credentials(it.profile) }.distinct().size > 1
            }) { "Accounts sharing a SIP identity must use the same credentials." }
            profile = prefs.profile()
            logSecret = appliedAccounts.map { it.profile.password }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
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
            // Credentials are persisted only by Sippy's encrypted account store, never by the SDK.
            engine.config.setInt("sip", "store_auth_info", 0)
            engine.setUserAgent("Sippy", packageManager.getPackageInfo(packageName, 0).versionName)
            engine.setMaxCalls(4)
            engine.setKeepAliveEnabled(true)
            engine.setAutoIterateEnabled(true)
            engine.setVideoCaptureEnabled(false)
            engine.setVideoDisplayEnabled(false)
            engine.setEchoCancellationEnabled(profile.echoCancellation)
            engine.setIpv6Enabled(profile.ipv6)
            val transports = engine.transports
            // -1 lets Linphone bind a random free port; 0 disables the transport.
            val port = profile.localPort.takeIf { it != 0 } ?: -1
            val used = appliedAccounts.filter { it.profile.configured }.map { it.profile.transport }.toSet().ifEmpty { setOf(profile.transport) }
            transports.setUdpPort(if (Transport.UDP in used) port else 0)
            transports.setTcpPort(if (Transport.TCP in used) port else 0)
            transports.setTlsPort(if (Transport.TLS in used) port else 0)
            require(engine.setTransports(transports) == 0) { "Cannot bind the local SIP port." }
            engine.setAudioPort(profile.rtpPort.takeIf { it > 0 } ?: -1)
            updateRingtone()
            engine.addListener(listener)
            require(engine.start() == 0) { "Could not start the SIP engine." }
            // Core.start() reloads these defaults; apply saved/runtime policies afterward.
            engine.setUseRfc2833ForDtmf(profile.dtmf == Dtmf.RTP)
            engine.setUseInfoForDtmf(profile.dtmf == Dtmf.INFO)
            // Each call receives its account's mode and required-encryption flag.
            engine.setMediaEncryption(MediaEncryption.None)
            // Initial incoming offers must support both clear and encrypted accounts.
            // Required encryption is enforced on each individual offer/answer instead.
            engine.setMediaEncryptionMandatory(false)
            engine.callLogs.forEach(::recordCall)
            val payloads = engine.audioPayloadTypes
            availableCodecs = payloads.filter { it.isUsable }.map { it.mimeType.lowercase(Locale.ROOT) }.toSet()
            applyCodecs(requireEnabled = appliedAccounts.any { it.profile.configured })
            for (configured in appliedAccounts.filter { it.profile.configured }) {
                val p = configured.profile
                p.validate()
                val params = engine.createAccountParams()
                val identity = address("sip:${p.username}@${p.identityDomain}")
                if (p.displayName.isNotBlank()) check(identity.setDisplayName(p.displayName) == 0) {
                    "Invalid SIP display name."
                }
                check(params.setIdentityAddress(identity) == 0) { "Invalid SIP identity." }
                val nextHop = p.proxy.ifBlank { p.registrar }
                // Linphone 5.4 uses the account server address as the REGISTER next hop.
                // The account identity still supplies the registration domain.
                check(params.setServerAddress(serverAddress(nextHop, p = p)) == 0) { "Invalid SIP server." }
                if (p.proxy.isNotBlank()) {
                    val route = serverAddress(nextHop, looseRoute = true, p = p)
                    check(params.setRoutesAddresses(arrayOf(route)) == 0) { "Invalid outbound proxy." }
                }
                params.setRealm(p.realm.ifBlank { null })
                params.setExpires(p.expiry)
                params.setRegisterEnabled(p.register)
                if (p.ice || p.stun.isNotBlank()) {
                    val nat = engine.createNatPolicy()
                    nat.setIceEnabled(p.ice)
                    if (p.stun.isNotBlank()) {
                        nat.setStunServer(p.stun)
                        nat.setStunEnabled(true)
                    }
                    params.setNatPolicy(nat)
                }

                if (p.password.isNotEmpty()) {
                    // Linphone 5.4 searches learned realms before domains. Private, fixed lookup
                    // realms prevent one server's challenge from selecting another account's key.
                    // Keep one entry per supported algorithm, while the SIP challenge supplies
                    // the real digest realm. SDK credential persistence/HA1 rewriting is disabled.
                    for (algorithm in listOf("MD5", "SHA-256")) {
                        val auth = f.createAuthInfo(p.username, p.authUser.ifBlank { null }, p.password, null,
                            "sippy-${java.util.UUID.randomUUID()}", p.identityDomain)
                        auth.setAlgorithm(algorithm)
                        engine.addAuthInfo(auth)
                    }
                }
                val account = engine.createAccount(params)
                val initialStatus = if (p.register) "Registering…" else "Direct SIP"
                registrations[configured.id] = AccountStatus(configured.id, configured.name, initialStatus, !p.register)
                activityStore.event(EventKind.INFO, "${configured.name} · $initialStatus · ${p.transport} · $nextHop")
                check(engine.addAccount(account) == 0) { "Could not add the SIP account." }
                nativeAccounts[configured.id] = account
            }
            selectOutgoing()
            dndSync = DndSync(engine, handler, prefs::dndEnabled, { registrations[it]?.ready == true }, { calls().isNotEmpty() },
                { account, raw -> destination(raw, account.profile) },
                { id, enabled ->
                    val accepted = if (ManagedConfiguration.isManaged("doNotDisturb")) prefs.dndEnabled(id) == enabled
                        else prefs.setDndEnabled(id, enabled)
                    if (accepted) enforceDoNotDisturb()
                    accepted
                }, ::publish, { activityStore.event(EventKind.WARNING, it, logSecret) })
            dndSync?.update(appliedAccounts, nativeAccounts)
            managedDndOverride = ManagedConfiguration.values["doNotDisturb"] as? Boolean
            if (managedDndOverride != null) appliedAccounts.forEach { dndSync?.localStateChanged(it.id) }
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

    internal fun updateIncomingVibration() {
        if (incomingLine != null) incomingRingtone.setVibrationEnabled(prefs.vibrateOnIncoming)
    }

    private fun recordCall(log: CallLog) {
        if (factoryResetting) return
        if (log.refKey == "sippy-dnd-fac" || dndSync?.internalLog(log) == true) return
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

    private var codecAccount: String? = null
    private inline fun <T> withAccountCodecs(id: String?, operation: () -> T): T {
        codecAccount = id
        try { applyCodecs(); return operation() }
        finally { codecAccount = null; applyCodecs(requireEnabled = false) }
    }
    internal fun applyCodecs(requireEnabled: Boolean = true) {
        val engine = core ?: return
        val payloads = engine.audioPayloadTypes
        val order = if (codecAccount != null) prefs.accounts().firstOrNull { it.id == codecAccount }?.options?.codecs ?: prefs.codecs()
            else Preferences.defaultCodecs.map { codec -> codec.copy(enabled = prefs.accounts().any { account -> account.options.codecs.any { it.id == codec.id && it.enabled } }) }
        val priority = order.mapIndexed { index, codec -> codec.id to index }.toMap()
        val enabled = order.filter { it.enabled }.map { it.id }.toSet()
        fun id(mime: String) = mime.lowercase(Locale.ROOT)
        require(!requireEnabled || availableCodecs.any { it in enabled }) { "Enable at least one available codec." }
        val sorted = payloads.sortedBy { priority[id(it.mimeType)] ?: Int.MAX_VALUE }
        sorted.forEach { it.enable(id(it.mimeType) in enabled && it.isUsable) }
        engine.setAudioPayloadTypes(sorted.toTypedArray())
        appliedCodecs = order
    }

    internal fun dial(raw: String, consultSource: Long? = null) = command {
        val configured = consultSource?.let { find(it)?.let(::accountFor) } ?: selectedConfig()
        check(configured != null && registrations[configured.id]?.ready == true) { "Connect the selected SIP account before calling." }
        check(calls().size < 4) { "All four call lines are in use." }
        check(consultSource == null || consultation == null) { "Finish or cancel the current consultation first." }
        val engine = checkNotNull(core)
        dndSync?.beforeUserCall()
        val target = destination(raw, configured.profile)
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
                    val params = requireNotNull(engine.createCallParams(null)).apply {
                        setVideoEnabled(false)
                        setAccount(nativeAccounts[configured.id])
                    }
                    CallEncryption.apply(engine, params, configured.profile.encryption)
                    val call = withAccountCodecs(configured.id) { engine.inviteAddressWithParams(target, params) } ?: error("Could not start the call.")
                    launched = true
                    prefs.lastDialedNumber = raw
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
                    CallEncryption.apply(checkNotNull(core), params, callEncryptions[id] ?: Encryption.None)
                    check(withAccountCodecs(accountFor(incoming)?.id) { incoming.acceptWithParams(params) } == 0) { "Could not answer the call." }
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
            withAccountCodecs(accountFor(call)?.id) { call.pause() } == 0) { "Could not hold this call." }
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
                val resumed = find(id)?.let { withAccountCodecs(accountFor(it)?.id) { it.resume() } } == 0
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
        val destination = destination(raw, accountFor(call)?.profile ?: profile)
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
            if (withAccountCodecs(accountFor(line)?.id) { line.resume() } == 0) selected = id
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
        if (withAccountCodecs(accountFor(call)?.id) { call.pause() } != 0) {
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

    private fun destination(raw: String, p: SipProfile = profile): Address {
        val target = requireNotNull(dialTarget(raw)) { "Enter a number or SIP address." }
        if ('@' in target) return address(if (target.contains(':') && target.substringBefore(':').lowercase() in setOf("sip", "sips")) target else "sip:$target")
        val domain = if (p.register) p.identityDomain else p.domain
        return address("sip:${target.replace("#", "%23")}@$domain")
    }

    private fun address(uri: String): Address =
        requireNotNull(factory?.createAddress(uri)) { "Invalid SIP address." }

    private fun serverAddress(host: String, looseRoute: Boolean = false, p: SipProfile = profile): Address =
        address(if (looseRoute) "sip:$host;lr" else "sip:$host").apply {
            check(setTransport(when (p.transport) {
                Transport.UDP -> TransportType.Udp
                Transport.TCP -> TransportType.Tcp
                Transport.TLS -> TransportType.Tls
            }) == 0) { "Invalid SIP transport." }
            check(!looseRoute || hasUriParam("lr")) { "Invalid outbound proxy route." }
        }

    private fun calls() = core?.calls.orEmpty().filter {
        it.state !in setOf(Call.State.End, Call.State.Error, Call.State.Released) && dndSync?.internalCall(it) != true
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
        if (current.isEmpty()) dndSync?.idle()
        if (current.isEmpty() && managedUpdatePending) {
            configure()
            return
        }
        publish()
    }

    private fun publish() {
        callProximity.update(calls().any { call ->
            phase(call.state) in setOf(Phase.DIALING, Phase.RINGING, Phase.CONNECTING, Phase.ACTIVE, Phase.REMOTE_HOLD) &&
                (call.outputAudioDevice ?: core?.outputAudioDevice)?.type == AudioDevice.Type.Earpiece
        })
        val lines = calls().map { call ->
            val address = call.remoteAddress
            val number = address.username ?: address.asStringUriOnly()
            Line(call.nativePointer, address.displayName ?: number, number, phase(call.state), call.duration,
                call.microphoneMuted, call.outputAudioDevice?.type == AudioDevice.Type.Speaker, accountFor(call)?.name ?: selectedName())
        }
        state = SipState(status, ready, lines, selected, consultation, availableCodecs,
            pending != null || transferPending != null, registrationDetail,
            appliedAccounts.map { registrations[it.id] ?: AccountStatus(it.id, it.name, "Not configured", false) },
            selectedConfig()?.id.orEmpty(), selectedName(), appliedAccounts.filter { prefs.dndEnabled(it.id) }.map { it.id }.toSet())
        if (activeNotification) {
            val line = if (microphoneForeground) lines.firstOrNull { it.id == selected } ?: lines.firstOrNull() else null
            val signature = Triple("${line?.accountName.orEmpty()} · ${selectedName()} · ${line?.name.orEmpty()} · ${dndLabel()}", line?.phase?.label ?: status, line != null)
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
                calls().firstOrNull { it.nativePointer == ringing.id }?.let(::accountFor)?.let { config ->
                    prefs.accounts().firstOrNull { it.id == config.id }?.options?.ringtoneUri
                }?.let(Uri::parse), prefs.vibrateOnIncoming)
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
            .setContentText(if (incoming) "${line?.accountName} · Tap to answer or decline" else "${line?.accountName ?: selectedName()} · ${line?.phase?.label ?: "Connecting"}${if (line != null && line.accountName != selectedName()) " · Outgoing: ${selectedName()}" else ""}${dndLabel().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}")
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
            .setContentTitle("Sippy · ${selectedName()}${dndLabel().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}")
            .setContentText("$status · ${registrations.values.count { it.ready }}/${registrations.size} accounts available" +
                if (dndNames().isNotEmpty()) " · DND calls declined" else " · Incoming calls enabled")
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
