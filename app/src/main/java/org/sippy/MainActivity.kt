package org.sippy

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import android.widget.FrameLayout
import android.graphics.Color
import android.text.TextWatcher
import android.text.Editable
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {
    private enum class Page(val label: String, val icon: String) {
        DIALER("Dialer", "dial"), CONTACTS("Contacts", "contacts"), SETTINGS("Settings", "settings"), HISTORY("History", "history")
    }
    private enum class AppPermission(val label: String, val manifestName: String, val minApi: Int = 23) {
        MICROPHONE("Microphone", Manifest.permission.RECORD_AUDIO),
        CONTACTS("Contacts", Manifest.permission.READ_CONTACTS),
        NOTIFICATIONS("Notifications", Manifest.permission.POST_NOTIFICATIONS, 33)
    }

    private enum class PermissionRequest(val code: Int) {
        FIRST_RUN(20), REVIEW(21), CALL(22), CONTACTS(23)
    }

    internal lateinit var ui: Ui
    internal lateinit var prefs: Preferences
    internal lateinit var directory: Directory
    internal val settingsFiles by lazy { SettingsFiles(this) }
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var topStatus: TextView
    private lateinit var banner: FrameLayout
    private var page = Page.DIALER
    private var showEvents = false
    private var service: SipService? = null
    private var bound = false
    private var snapshot = SipState()
    private var showCall = false
    private var shownIncoming: Long? = null
    private var number = ""
    private var dialerInput: EditText? = null
    private var dialpadToneGenerator: ToneGenerator? = null
    private var showDtmf = false
    private var tones = ""
    private var lastSelected: Long? = null
    private var query = ""
    private var source: Source? = null
    private var sort = Sort.NAME_ASC
    private var phoneEntries = emptyList<Entry>()
    private var permissionAction: (() -> Unit)? = null
    private var pendingNotificationAnswer: Long? = null
    private var answerUiReady = false
    private val backNavigation = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                showEvents && page == Page.SETTINGS -> { showEvents = false; renderPage() }
                showCall && page == Page.DIALER -> { showCall = false; renderPage(); updateBanner() }
                page != Page.DIALER -> navigate(Page.DIALER)
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
    }
    private var permissionsInFlight = false
    private var observersAttached = false
    private var refreshContacts: (() -> Unit)? = null
    internal var refreshSettingsStatus: (() -> Unit)? = null
    internal var settingsTab = 0
    private val handler = Handler(Looper.getMainLooper())

    internal val sip get() = service
    internal val sipState get() = snapshot
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("entries_") == true) refreshDirectoryViews()
        if (key in setOf("lastSync", "syncError", "csvUrl")) refreshSettingsStatus?.invoke()
    }

    private val contactObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = loadPhoneContacts()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as SipService.LocalBinder).service.apply {
                onChange = ::changed
                onMessage = ::message
            }
            changed(service!!.state)
            answerFromNotification()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            changed(SipState(status = "SIP disconnected"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Preferences(this)
        applyAppearance()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backNavigation)
        ui = Ui(this)
        directory = Directory(this)
        page = Page.entries.getOrNull(savedInstanceState?.getInt("page") ?: 0) ?: Page.DIALER
        settingsTab = savedInstanceState?.getInt("settingsTab") ?: 0
        showEvents = savedInstanceState?.getBoolean("showEvents") == true
        number = savedInstanceState?.getString("number").orEmpty()
        settingsFiles.includePassword = savedInstanceState?.getBoolean("exportPassword") == true
        permissionsInFlight = savedInstanceState?.getBoolean("permissionsInFlight") == true
        val notificationIntentAnswer = takeNotificationAnswer(intent)
        pendingNotificationAnswer = savedInstanceState?.getLong("notificationAnswer")?.takeIf { it != 0L }
            ?: notificationIntentAnswer
        showCall = intent.action == SipService.OPEN_CALL || pendingNotificationAnswer != null || savedInstanceState?.getBoolean("call") == true
        if (showCall) page = Page.DIALER

        buildLayout()
        prefs.data.registerOnSharedPreferenceChangeListener(changes)
        bound = bindService(Intent(this, SipService::class.java), connection, BIND_AUTO_CREATE)
        runCatching { directory.schedule() }.onFailure { message(it.localizedMessage ?: "CSV sync could not be scheduled.") }
        attachContacts()
    }

    internal fun setAppearance(appearance: Appearance) {
        if (prefs.appearance == appearance) return
        prefs.appearance = appearance
        applyAppearance()
        buildLayout()
    }

    private fun applyAppearance() {
        val dark = when (prefs.appearance) {
            Appearance.LIGHT -> false
            Appearance.DARK -> true
            Appearance.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        Palette.useDark(dark)
        setTheme(if (dark) R.style.SippyDarkTheme else R.style.SippyTheme)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.appearance == Appearance.SYSTEM) {
            applyAppearance()
            buildLayout()
        }
    }

    private fun buildLayout() {
        root = ui.column().apply {
            setBackgroundColor(Palette.background)
            isFocusableInTouchMode = true
        }
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Palette.isDark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                if (::nav.isInitialized) nav.visibility = if (keyboard.bottom > bars.bottom) View.GONE else View.VISIBLE
                insets
            }
        }
        root.addView(header(), LinearLayout.LayoutParams(-1, -2))
        banner = FrameLayout(this)
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = ui.row().apply { setBackgroundColor(Palette.surface) }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.requestFocus()
        renderPage()
        renderNav()
        changed(snapshot)
    }

    override fun onResume() {
        super.onResume()
        attachContacts()
        timer.run()
    }

    override fun onPostResume() {
        super.onPostResume()
        answerUiReady = true
        answerFromNotification()
        if (!permissionsInFlight && !prefs.data.getBoolean("firstRunPermissionsChecked", false)) {
            prefs.data.edit().putBoolean("firstRunPermissionsChecked", true).apply()
            requestAppPermissions(PermissionRequest.FIRST_RUN, AppPermission.entries)
        }
    }

    override fun onPause() {
        answerUiReady = false
        handler.removeCallbacks(timer)
        settingsFiles.ringtonePreview.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        settingsFiles.close()
        dialpadToneGenerator?.release()
        prefs.data.unregisterOnSharedPreferenceChangeListener(changes)
        if (observersAttached) contentResolver.unregisterContentObserver(contactObserver)
        if (bound) {
            service?.let {
                if (it.onChange == this::changed) { it.onChange = null; it.onMessage = null }
            }
            unbindService(connection)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page.ordinal)
        outState.putInt("settingsTab", settingsTab)
        outState.putBoolean("showEvents", showEvents)
        outState.putString("number", number)
        outState.putBoolean("exportPassword", settingsFiles.includePassword)
        outState.putBoolean("permissionsInFlight", permissionsInFlight)
        outState.putBoolean("call", showCall)
        outState.putLong("notificationAnswer", pendingNotificationAnswer ?: 0L)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeNotificationAnswer(intent)?.let { pendingNotificationAnswer = it }
        if (intent.action == SipService.OPEN_CALL) openCall()
        answerFromNotification()
    }

    private fun takeNotificationAnswer(intent: Intent): Long? {
        if (intent.action != SipService.ACCEPT_CALL) return null
        intent.action = SipService.OPEN_CALL
        return intent.getLongExtra("line", 0L).takeIf { it != 0L }
    }

    private fun answerFromNotification() {
        val id = pendingNotificationAnswer ?: return
        val engine = service ?: return
        if (!answerUiReady || permissionsInFlight) return
        pendingNotificationAnswer = null
        if (snapshot.lines.none { it.id == id && it.phase == Phase.INCOMING }) {
            message("This call is no longer ringing.")
            return
        }
        withMicrophone { engine.answer(id) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val input = dialerInput
        return (input != null && dispatchDialKey(input, event)) || super.onKeyDown(keyCode, event)
    }

    private var callClock: TextView? = null
    private var clockAnchor = 0L
    private var clockSeconds = 0
    private val timer = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            callClock?.text = duration(clockSeconds + ((android.os.SystemClock.elapsedRealtime() - clockAnchor) / 1000).toInt())
            handler.postDelayed(this, 1000)
        }
    }

    private fun changed(newState: SipState) {
        val previous = snapshot
        snapshot = newState
        if (newState.lines.isNotEmpty()) settingsFiles.ringtonePreview.stop()
        val incoming = newState.lines.firstOrNull { it.phase == Phase.INCOMING }
        if (incoming?.id != shownIncoming) {
            shownIncoming = incoming?.id
            if (incoming != null && page == Page.DIALER && !showCall) showCall = true
        }
        val selection = focusLine()?.id
        if (lastSelected != selection) {
            showDtmf = false
            tones = ""
            lastSelected = selection
        }
        val closing = showCall && newState.lines.isEmpty() && previous.lines.isNotEmpty()
        if (closing) showCall = false
        val oldVisible = root.findViewWithTag<View>("call-screen") != null
        if (page == Page.DIALER && (closing || showCall && (previous.controls() != newState.controls() || !oldVisible))) {
            renderPage(keepCallScroll = oldVisible && showCall && !closing && previous.selected == newState.selected)
        }
        if (page == Page.SETTINGS && !showEvents && settingsTab == 2 && previous.availableCodecs != newState.availableCodecs) renderPage()
        topStatus.text = newState.status
        topStatus.contentDescription = if (newState.registrationDetail != null) {
            "${newState.status}. Tap for details."
        } else newState.status
        topStatus.setTextColor(if (newState.ready) Palette.accent else Palette.secondary)
        updateBanner()
    }

    private fun focusLine(): Line? = snapshot.lines.firstOrNull { it.phase == Phase.INCOMING }
        ?: snapshot.lines.firstOrNull { it.id == snapshot.selected }
        ?: snapshot.lines.firstOrNull()

    private fun header() = ui.row(11).apply {
        setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10))
        val logo = ImageView(this@MainActivity).apply {
            background = ui.shape(getColor(R.color.sippy_icon_background), 14)
            clipToOutline = true
            setImageResource(R.drawable.sippy_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Sippy logo"
        }
        addView(logo, LinearLayout.LayoutParams(ui.dp(49), ui.dp(49)))
        addView(ui.text("Sippy", 27, weight = true), ui.weight())
        topStatus = ui.text(snapshot.status, 12, Palette.secondary, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = ui.dp(173)
            setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
            background = ui.shape(Palette.soft, 12)
            setOnClickListener {
                val detail = snapshot.registrationDetail
                if (detail == null) {
                    settingsTab = 0
                    navigate(Page.SETTINGS)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(snapshot.status).setMessage(detail)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Account settings") { _, _ -> settingsTab = 0; navigate(Page.SETTINGS) }
                        .show()
                }
            }
        }
        addView(topStatus)
    }

    private fun renderNav() {
        nav.removeAllViews()
        nav.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
        listOf(Page.DIALER, Page.CONTACTS, Page.HISTORY, Page.SETTINGS).forEach { screen ->
            val selected = page == screen
            val tint = if (selected) Palette.accent else Palette.secondary
            val item = ui.column(4).apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(54)
                background = ui.ripple(if (selected) Palette.soft else Palette.surface, 15)
                addView(Symbol(this@MainActivity, screen.icon, tint), LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)))
                addView(ui.text(screen.label, 12, tint, selected).apply { gravity = Gravity.CENTER })
                contentDescription = screen.label
                isSelected = selected
                isFocusable = true
                setOnClickListener { navigate(screen) }
            }
            nav.addView(item, ui.weight().apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
        }
    }

    private fun navigate(target: Page) {
        hideKeyboard()
        page = target
        showEvents = false
        if (target == Page.DIALER) showCall = snapshot.lines.isNotEmpty()
        renderPage()
        renderNav()
        updateBanner()
    }

    private fun openCall() {
        showCall = true
        page = Page.DIALER
        renderPage()
        renderNav()
        updateBanner()
    }

    private fun updateBanner() {
        banner.removeAllViews()
        val line = focusLine()
        if (line == null || (showCall && page == Page.DIALER)) return
        val label = if (line.phase == Phase.INCOMING) "Incoming call · ${line.name}" else "${line.phase.label} · ${line.name}"
        banner.addView(ui.action(label, "call", Palette.dark, Palette.mint, click = ::openCall),
            FrameLayout.LayoutParams(-1, -2).apply {
                setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(5))
            })
    }

    private fun SipState.controls() = copy(lines = lines.map { it.copy(seconds = 0) })

    internal fun renderPage(keepCallScroll: Boolean = false) {
        backNavigation.isEnabled = page != Page.DIALER || showCall
        settingsFiles.ringtonePreview.stop()
        settingsFiles.ringtonePreview.onChange = null
        val scrollPosition = if (keepCallScroll) (body.getChildAt(0) as? ScrollView)?.scrollY else null
        refreshContacts = null
        refreshSettingsStatus = null
        callClock = null
        dialerInput = null
        body.removeAllViews()
        val view = when (page) {
            Page.DIALER -> if (showCall && snapshot.lines.isNotEmpty()) scroll(callScreen()) else scroll(dialer())
            Page.CONTACTS -> contacts()
            Page.HISTORY -> ActivityScreens(this).history(::confirmDial)
            Page.SETTINGS -> if (showEvents) ActivityScreens(this).events() else SettingsScreen(this).build()
        }
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (scrollPosition != null) view.post { view.scrollTo(0, scrollPosition) }
    }

    internal fun openEventLog() {
        showEvents = true
        renderPage()
    }

    internal fun scroll(view: View) = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(view, ViewGroup.LayoutParams(-1, -2))
    }

    private fun padded(gap: Int = 14) = ui.column(gap).apply {
        setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(18))
    }

    private fun dialer() = padded().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.heading("Dialer", "Enter an extension, number, or SIP address."))
        val input = ui.field("Number or SIP address", number, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setOnKeyListener { _, _, event -> dispatchDialKey(this, event) }
            gravity = Gravity.CENTER
            textSize = 24f
            minimumHeight = ui.dp(62)
            showSoftInputOnFocus = false
            setSelection(length())
            watch { number = it }
            setOnLongClickListener {
                showSoftInputOnFocus = true
                requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                true
            }
        }
        dialerInput = input
        addView(input)
        addView(keypad { enterDialDigit(input, it) })
        addView(ui.row(8).apply {
            addView(ui.action("Clear", fill = Palette.surface, outlined = true) { input.text.clear() })
            addView(ui.action("Contacts", "contacts", outlined = true, fill = Palette.surface) {
                chooseDestination("Call a contact", "Call", fromPhonebook = true) { dial(it) }
            }, ui.weight())
            addView(ui.action("Call", "call", Palette.accent, Palette.onAccent) {
                val target = dialTarget(number) ?: return@action message("Enter a number or SIP address.")
                dial(target)
            }, ui.weight())
            val back = ui.action("", "backspace", outlined = true, fill = Palette.surface) { erase(input) }.apply {
                contentDescription = "Delete last digit"
                setOnLongClickListener { input.setText(""); true }
            }
            addView(back, LinearLayout.LayoutParams(ui.dp(50), -2))
        })
    }

    private fun keypad(letters: Boolean = true, pressed: (Char) -> Unit): LinearLayout = ui.column(6).apply {
        val captions = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "")
        "123456789*0#".chunked(3).forEachIndexed { rowIndex, digits ->
            addView(ui.row(7).apply {
                digits.forEachIndexed { index, digit ->
                    val key = ui.column().apply {
                        gravity = Gravity.CENTER
                        minimumHeight = ui.dp(48)
                        background = ui.ripple(Palette.surface, 13, Palette.border)
                        addView(ui.text(digit.toString(), 23, weight = true).apply { gravity = Gravity.CENTER })
                        val caption = captions[rowIndex * 3 + index]
                        if (letters && caption.isNotEmpty()) addView(ui.text(caption, 9, Palette.secondary, true).apply { gravity = Gravity.CENTER })
                        contentDescription = when (digit) { '*' -> "Star"; '#' -> "Pound"; else -> digit.toString() }
                        isFocusable = true
                        if (letters) isSoundEffectsEnabled = false
                        setOnClickListener { pressed(digit) }
                        if (letters && digit == '0') setOnLongClickListener { pressed('+'); true }
                    }
                    addView(key, ui.weight())
                }
            })
        }
    }

    private fun callScreen() = padded(12).apply {
        tag = "call-screen"
        val line = focusLine() ?: return@apply
        addView(ui.heading(if (line.phase == Phase.INCOMING) "Incoming call" else "Call controls"))
        val identity = ui.column(8).apply {
            background = ui.shape(Palette.dark, 21)
            ui.pad(this, 18)
            addView(ui.text(line.phase.label, 13, Palette.mint, true))
            addView(ui.text(line.name, 27, Color.WHITE, true))
            if (line.address != line.name) addView(ui.text(line.address, 15, Palette.mint))
            if (line.phase in setOf(Phase.ACTIVE, Phase.HELD, Phase.HOLDING, Phase.REMOTE_HOLD, Phase.RESUMING)) {
                clockAnchor = android.os.SystemClock.elapsedRealtime()
                clockSeconds = line.seconds
                callClock = ui.text(duration(clockSeconds), 15, Palette.mint).also { addView(it) }
            }
        }
        addView(identity)
        if (snapshot.lines.size > 1) {
            addView(ui.text("Call lines", 14, Palette.secondary, true))
            snapshot.lines.forEach { item ->
                val current = item.id == line.id
                val label = "${item.name} · ${item.phase.label}"
                addView(ui.action(label, if (current) "call" else "chevron",
                    if (current) Palette.soft else Palette.surface, outlined = !current) {
                    if (!current) service?.select(item.id)
                })
            }
        }
        if (line.phase == Phase.INCOMING) {
            addView(ui.row(8).apply {
                addView(ui.action("Answer", "call", Palette.accent, Palette.onAccent) {
                    withMicrophone { service?.answer(line.id) }
                }, ui.weight())
                addView(ui.action("Decline", "hangup", Palette.dangerSoft, Palette.danger) {
                    service?.end(line.id)
                }, ui.weight())
            })
            return@apply
        }

        val connected = line.phase == Phase.ACTIVE
        val held = line.phase == Phase.HELD
        if (connected || held) {
            addView(ui.row(8).apply {
                addView(ui.action(if (held) "Resume" else "Hold", if (held) "resume" else "hold") {
                    if (held) service?.select(line.id) else service?.hold(line.id)
                }, ui.weight())
                addView(ui.action(if (showDtmf) "Hide keypad" else "Keypad", "dial",
                    fill = if (showDtmf) Palette.accent else Palette.soft, tint = if (showDtmf) Palette.onAccent else Palette.accent) {
                    if (!connected) { message("Resume this line to send DTMF."); return@action }
                    showDtmf = !showDtmf
                    renderPage()
                }, ui.weight())
            })
        }
        if (connected && showDtmf) {
            val output = ui.text(tones.ifEmpty { "DTMF keypad" }, 15, Palette.secondary, true).apply { gravity = Gravity.CENTER }
            addView(output)
            addView(keypad(false) { tone ->
                tones = (tones + tone).takeLast(24)
                output.text = tones
                service?.digits(line.id, tone)
            })
        }
        if (connected) {
            addView(ui.row(8).apply {
                addView(ui.action(if (line.muted) "Unmute" else "Mute", "mic", fill = if (line.muted) Palette.soft else Palette.surface, outlined = true) {
                    service?.mute(line.id)
                }, ui.weight())
                addView(ui.action(if (line.speaker) "Speaker on" else "Speaker", "volume", fill = if (line.speaker) Palette.soft else Palette.surface, outlined = true) {
                    service?.speaker(line.id)
                }, ui.weight())
            })
        }
        val consultation = snapshot.consultation
        if (consultation != null) {
            addView(ui.text("Consultation transfer", 15, Palette.secondary, true))
            addView(ui.row(8).apply {
                val canComplete = listOf(consultation.source, consultation.target).all { id ->
                    snapshot.lines.any { it.id == id && it.phase in setOf(Phase.ACTIVE, Phase.HELD) }
                }
                addView(ui.action("Complete", "transfer", Palette.accent, Palette.onAccent) { service?.completeTransfer() }
                    .apply { toggleEnabled(canComplete && !snapshot.busy) }, ui.weight())
                addView(ui.action("Cancel", fill = Palette.surface, outlined = true) { service?.cancelTransfer() }
                    .apply { toggleEnabled(!snapshot.busy) }, ui.weight())
            })
        } else if (connected || held || line.phase == Phase.REMOTE_HOLD) {
            addView(ui.row(8).apply {
                addView(ui.action("New call", "plus", Palette.surface, outlined = true) {
                    chooseDestination("New call", "Call") { dial(it) }
                }.apply { toggleEnabled(!snapshot.busy && snapshot.lines.size < 4) }, ui.weight())
                addView(ui.action("Transfer", "transfer", Palette.surface, outlined = true) { transferMenu(line.id) }
                    .apply { toggleEnabled(!snapshot.busy && (connected || held)) }, ui.weight())
            })
        }
        addView(ui.action("End call", "hangup", Palette.danger, Palette.onDanger) { service?.end(line.id) })
    }

    private fun View.toggleEnabled(enable: Boolean) { isEnabled = enable; alpha = if (enable) 1f else .42f }

    private fun transferMenu(id: Long) {
        AlertDialog.Builder(this).setTitle("Transfer call")
            .setItems(arrayOf("Direct transfer", "Ask first")) { _, choice ->
                if (choice == 0) chooseDestination("Direct transfer", "Transfer") { service?.blind(id, it) }
                else chooseDestination("Ask first", "Call") { dial(it, id) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dial(target: String, consultation: Long? = null) {
        val engine = service ?: return message("SIP is still starting.")
        if (!snapshot.ready) {
            message(snapshot.status)
            return
        }
        withMicrophone {
            openCall()
            engine.dial(target, consultation)
        }
    }

    internal fun checkPermissions() = requestAppPermissions(PermissionRequest.REVIEW, AppPermission.entries)

    private fun withMicrophone(action: () -> Unit) {
        if (hasPermission(AppPermission.MICROPHONE)) {
            action()
        } else if (!permissionsInFlight) {
            permissionAction = action
            requestAppPermissions(PermissionRequest.CALL, listOf(AppPermission.MICROPHONE))
        }
    }

    private fun hasPermission(permission: AppPermission) = when (permission) {
        AppPermission.NOTIFICATIONS -> getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        else -> checkSelfPermission(permission.manifestName) == PackageManager.PERMISSION_GRANTED
    }

    private fun needsRuntimeGrant(permission: AppPermission) =
        Build.VERSION.SDK_INT >= permission.minApi &&
            checkSelfPermission(permission.manifestName) != PackageManager.PERMISSION_GRANTED

    private fun requestAppPermissions(request: PermissionRequest, permissions: List<AppPermission>) {
        if (permissionsInFlight) return
        val missing = permissions.filterNot(::hasPermission)
        val runtime = missing.filter(::needsRuntimeGrant)
        when {
            runtime.isNotEmpty() -> {
                permissionsInFlight = true
                requestPermissions(runtime.map { it.manifestName }.toTypedArray(), request.code)
            }
            request == PermissionRequest.REVIEW && missing.isEmpty() -> message("All Sippy permissions are enabled.")
            request == PermissionRequest.REVIEW && missing.isNotEmpty() -> offerPermissionSettings(missing)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val request = PermissionRequest.entries.firstOrNull { it.code == requestCode } ?: return
        permissionsInFlight = false
        attachContacts()
        refreshDirectoryViews()
        val next = permissionAction.also { permissionAction = null }
        if (hasPermission(AppPermission.MICROPHONE)) answerFromNotification()
        else pendingNotificationAnswer = null
        if (request == PermissionRequest.CALL && hasPermission(AppPermission.MICROPHONE)) {
            next?.invoke()
            return
        }
        if (request == PermissionRequest.FIRST_RUN || grantResults.isEmpty()) return

        val checked = when (request) {
            PermissionRequest.REVIEW -> AppPermission.entries
            PermissionRequest.CALL -> listOf(AppPermission.MICROPHONE)
            PermissionRequest.CONTACTS -> listOf(AppPermission.CONTACTS)
            PermissionRequest.FIRST_RUN -> emptyList()
        }
        val missing = checked.filterNot(::hasPermission)
        val blocked = missing.filter { !needsRuntimeGrant(it) || !shouldShowRequestPermissionRationale(it.manifestName) }
        when {
            blocked.isNotEmpty() -> offerPermissionSettings(blocked)
            request == PermissionRequest.CALL -> message("Sippy needs microphone permission for calls.")
            request == PermissionRequest.REVIEW && missing.isEmpty() -> message("All Sippy permissions are enabled.")
            request == PermissionRequest.REVIEW -> message("Still disabled: ${missing.joinToString { it.label }}.")
        }
    }

    private fun offerPermissionSettings(permissions: List<AppPermission>) {
        val labels = permissions.joinToString { it.label }
        val intent = if (permissions == listOf(AppPermission.NOTIFICATIONS)) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        AlertDialog.Builder(this).setTitle("Enable in Android settings")
            .setMessage("These permissions are still disabled: $labels.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(intent) }
            .show()
    }

    private fun attachContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            phoneEntries = emptyList()
            if (observersAttached) {
                contentResolver.unregisterContentObserver(contactObserver)
                observersAttached = false
            }
            refreshDirectoryViews()
            return
        }
        if (!observersAttached) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactObserver)
            observersAttached = true
        }
        loadPhoneContacts()
    }

    private fun loadPhoneContacts() {
        directoryExecutor.execute {
            val phone = directory.phone()
            runOnUiThread {
                if (!isDestroyed && hasPermission(AppPermission.CONTACTS)) {
                    phoneEntries = phone
                    refreshDirectoryViews()
                }
            }
        }
    }

    private fun contacts() = padded(10).apply {
        addView(ui.heading("Contacts", "Phone contacts and your own SIP directories."))
        val actions = ui.row(8)
        actions.addView(ui.action("Add contact", "plus") { editContact(null) }, ui.weight())
        actions.addView(ui.action("Import CSV", fill = Palette.surface, outlined = true, click = ::importCsv), ui.weight())
        addView(actions)
        val directoryView = directoryView(query, source, { item ->
            if (item.source == Source.LOCAL) localContactMenu(item) else confirmDial(item.number, item.name)
        }, { query = it }, { source = it })
        addView(directoryView.view, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshContacts = directoryView.refresh
    }

    private data class DirectoryView(val view: View, val refresh: () -> Unit)
    private fun directoryView(startQuery: String, startSource: Source?, chosen: (Entry) -> Unit,
        updateQuery: (String) -> Unit = {}, updateSource: (Source?) -> Unit = {}): DirectoryView {
        var text = startQuery
        var filter = startSource
        val outer = ui.column(8)
        val search = ui.field("Search name or number", text)
        outer.addView(search)
        val count = ui.text("", 13, Palette.secondary, true)
        val controls = ui.row(6)
        outer.addView(controls)
        val permission = ui.action("Allow phone contacts", "contacts") {
            requestAppPermissions(PermissionRequest.CONTACTS, listOf(AppPermission.CONTACTS))
        }
        outer.addView(permission)
        val empty = ui.text("", 15, Palette.secondary).apply {
            gravity = Gravity.CENTER
            ui.pad(this, 12)
        }
        val list = ListView(this).apply {
            dividerHeight = ui.dp(5)
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val adapter = EntriesAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, index, _ -> chosen(adapter.getItem(index)) }
        val results = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        outer.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(count)
        fun refresh() {
            val all = buildList {
                addAll(phoneEntries)
                for (book in listOf(Source.LOCAL, Source.CSV, Source.SYNC)) addAll(directory.stored(book))
            }
            val items = searchEntries(all.filter { filter == null || it.source == filter }, text, sort)
            adapter.entries = items
            adapter.notifyDataSetChanged()
            count.text = "${items.size} ${if (items.size == 1) "contact" else "contacts"}"
            empty.text = if (text.isNotBlank()) "No matching contacts" else "No contacts in this phonebook"
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            permission.visibility = if ((filter == null || filter == Source.PHONE) &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) View.VISIBLE else View.GONE
        }
        fun filters() {
            controls.removeAllViews()
            val groups = listOf<Source?>(null) + Source.entries
            val sourceAction = ui.action(filter?.label ?: "All phonebooks", "down", Palette.surface, outlined = true) {}
            sourceAction.setOnClickListener {
                popup(sourceAction, groups.map { it?.label ?: "All phonebooks" }) {
                    filter = groups[it]; updateSource(filter); filters(); refresh()
                }
            }
            val sortAction = ui.action(sort.label, "down", Palette.surface, outlined = true) {}
            sortAction.setOnClickListener {
                popup(sortAction, Sort.entries.map { it.label }) { index -> sort = Sort.entries[index]; filters(); refresh() }
            }
            controls.addView(sourceAction, ui.weight())
            controls.addView(sortAction, ui.weight())
        }
        search.watch { text = it.trim(); updateQuery(it); refresh() }
        filters()
        refresh()
        return DirectoryView(outer, ::refresh)
    }

    private inner class EntriesAdapter : BaseAdapter() {
        var entries = emptyList<Entry>()
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            return ui.row(10).apply {
                background = ui.shape(Palette.surface, 13, Palette.border)
                setPadding(ui.dp(11), ui.dp(10), ui.dp(11), ui.dp(10))
                val avatar = ui.text(item.name.firstOrNull()?.uppercase() ?: "•", 16, Palette.accent, true).apply {
                    gravity = Gravity.CENTER
                    background = ui.shape(Palette.soft, 12)
                }
                addView(avatar, LinearLayout.LayoutParams(ui.dp(39), ui.dp(39)))
                addView(ui.column(4).apply {
                    addView(ui.text(item.name.ifBlank { item.number }, 15, weight = true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(ui.text("${if (item.name.isBlank()) "" else item.number + " · "}${item.source.label}", 12, Palette.secondary))
                }, ui.weight())
                addView(Symbol(this@MainActivity, "chevron", Palette.secondary),
                    LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
            }
        }
    }

    private val dialogDirectoryRefreshers = mutableSetOf<() -> Unit>()

    private fun refreshDirectoryViews() {
        refreshContacts?.invoke()
        dialogDirectoryRefreshers.toList().forEach { it() }
    }

    private fun chooseDestination(title: String, verb: String, fromPhonebook: Boolean = false, selected: (String) -> Unit) {
        val content = padded(8)
        val tabs = ui.row(6)
        val targetArea = FrameLayout(this)
        content.addView(tabs)
        content.addView(targetArea, LinearLayout.LayoutParams(-1, 0, 1f))
        content.minimumHeight = ui.dp(412)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(content).setNegativeButton("Cancel", null).create()
        var contactMode = fromPhonebook
        var remembered = ""
        var destinationInput: EditText? = null
        var refreshDirectory: (() -> Unit)? = null
        dialog.setOnKeyListener { _, _, event ->
            destinationInput?.let { dispatchDialKey(it, event) } ?: false
        }
        fun showMode() {
            refreshDirectory?.let { dialogDirectoryRefreshers.remove(it) }
            refreshDirectory = null
            destinationInput = null
            tabs.removeAllViews()
            targetArea.removeAllViews()
            listOf("Dialpad", "Phonebooks").forEachIndexed { index, label ->
                tabs.addView(ui.action(label, if (index == 0) "dial" else "contacts",
                    if ((index == 1) == contactMode) Palette.soft else Palette.surface, outlined = true) {
                    contactMode = index == 1
                    showMode()
                }, ui.weight())
            }
            if (contactMode) {
                val book = directoryView("", null, { entry ->
                    hiddenDialog(dialog)
                    val name = entry.name.ifBlank { entry.number }
                    val question = if (verb == "Transfer") "Transfer to $name?" else "Call $name?"
                    AlertDialog.Builder(this).setTitle(question)
                        .setMessage(entry.number.takeIf { it != name })
                        .setPositiveButton(verb) { _, _ -> selected(entry.number) }
                        .setNegativeButton("Cancel", null).show()
                })
                targetArea.addView(book.view, FrameLayout.LayoutParams(-1, -1))
                refreshDirectory = book.refresh
                dialogDirectoryRefreshers.add(book.refresh)
            } else {
                val input = ui.field("Number or SIP address", remembered, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                destinationInput = input
                input.setSelection(input.length())
                input.watch { remembered = it }
                val fields = ui.column(7)
                fields.isFocusableInTouchMode = true
                fields.addView(input)
                fields.addView(keypad { enterDialDigit(input, it) })
                fields.addView(ui.row(7).apply {
                    addView(ui.action("Clear", fill = Palette.surface, outlined = true) { input.text.clear() })
                    addView(ui.action(verb, if (verb == "Transfer") "transfer" else "call", Palette.accent, Palette.onAccent) {
                        val value = dialTarget(remembered) ?: return@action message("Enter a number or SIP address.")
                        hiddenDialog(dialog)
                        selected(value)
                    }, ui.weight())
                    addView(ui.action("", "backspace", Palette.surface, outlined = true) { erase(input) }.apply {
                        contentDescription = "Delete last digit"
                    }, LinearLayout.LayoutParams(ui.dp(50), -2))
                })
                targetArea.addView(scroll(fields), FrameLayout.LayoutParams(-1, -1))
                fields.requestFocus()
            }
        }
        dialog.setOnDismissListener { refreshDirectory?.let { dialogDirectoryRefreshers.remove(it) } }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        showMode()
    }

    private fun hiddenDialog(dialog: AlertDialog) {
        dialog.currentFocus?.windowToken?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
        dialog.dismiss()
    }

    private fun confirmDial(raw: String, name: String = "") {
        val display = name.ifBlank { raw }
        AlertDialog.Builder(this).setTitle("Call $display?")
            .setMessage(if (display == raw) null else raw)
            .setPositiveButton("Call") { _, _ -> dial(raw) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun localContactMenu(entry: Entry) {
        AlertDialog.Builder(this).setTitle(entry.name.ifBlank { entry.number })
            .setItems(arrayOf("Call", "Edit contact", "Delete contact")) { _, index ->
                when (index) {
                    0 -> confirmDial(entry.number, entry.name)
                    1 -> editContact(entry)
                    2 -> AlertDialog.Builder(this).setTitle("Delete this local contact?")
                        .setPositiveButton("Delete") { _, _ -> directory.removeLocal(entry.id) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editContact(entry: Entry?) {
        val name = ui.field("Name", entry?.name.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val number = ui.field("Number or SIP address", entry?.number.orEmpty())
        val fields = padded(10).apply { addView(name); addView(number) }
        val dialog = AlertDialog.Builder(this).setTitle(if (entry == null) "Add local contact" else "Edit local contact")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { directory.saveLocal(entry?.id, name.text.toString(), number.text.toString()) }
                .onSuccess { hiddenDialog(dialog) }
                .onFailure { number.error = it.localizedMessage }
        }
    }

    internal fun importCsv() {
        if (directory.stored(Source.CSV).isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Replace imported phonebook?")
                .setMessage("A successful import replaces the previous CSV phonebook.")
                .setPositiveButton("Choose file") { _, _ -> openCsv() }.setNegativeButton("Cancel", null).show()
        } else openCsv()
    }

    @Suppress("DEPRECATION")
    private fun openCsv() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 3)
    }

    @Deprecated("Storage Access Framework Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (settingsFiles.result(requestCode, resultCode, data)) return
        val uri = data?.data ?: return
        if (requestCode != 3 || resultCode != RESULT_OK) return
        directoryExecutor.execute {
            val outcome = runCatching { requireNotNull(contentResolver.openInputStream(uri)).use(directory::import) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                outcome.onSuccess { message("Imported $it contacts.") }.onFailure { message(it.localizedMessage ?: "CSV import failed.") }
            }
        }
    }

    internal fun syncNow(showOutcome: Boolean = true) {
        val config = directory.config()
        if (config.url.isEmpty()) { message("Save a CSV URL first."); return }
        if (showOutcome) message("Syncing phonebook…")
        directoryExecutor.execute {
            val result = runCatching { directory.fetch(config) }
            runOnUiThread {
                directory.complete(config, result)
                if (!isDestroyed && showOutcome && directory.config() == config) {
                    message(result.fold({ "Synced ${it.size} contacts." }, { "CSV sync failed. See Phonebook settings." }))
                }
            }
        }
    }

    private fun dispatchDialKey(input: EditText, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
        val digit = event.unicodeChar.toChar()
        when {
            digit in "0123456789*#+" -> enterDialDigit(input, digit)
            event.keyCode == KeyEvent.KEYCODE_DEL && !input.hasFocus() -> erase(input)
            else -> return false
        }
        return true
    }

    private fun enterDialDigit(input: EditText, digit: Char) {
        val start = input.selectionStart.takeIf { input.hasFocus() && it >= 0 } ?: input.length()
        val end = input.selectionEnd.takeIf { input.hasFocus() && it >= 0 } ?: start
        val cursor = minOf(start, end)
        input.text.replace(cursor, maxOf(start, end), digit.toString())
        input.setSelection(cursor + 1)

        if (!prefs.dialpadTones) return
        val tone = when (digit) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (digit - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            '+' -> ToneGenerator.TONE_DTMF_0
            else -> return
        }
        val generator = dialpadToneGenerator ?: try {
            ToneGenerator(AudioManager.STREAM_DTMF, 60).also { dialpadToneGenerator = it }
        } catch (_: RuntimeException) {
            return
        }
        generator.startTone(tone, 110)
    }
    private fun erase(input: EditText) {
        val a = input.selectionStart.takeIf { input.hasFocus() && it >= 0 } ?: input.length()
        val b = input.selectionEnd.takeIf { input.hasFocus() && it >= 0 } ?: a
        val start = if (a == b) (a - 1).coerceAtLeast(0) else minOf(a, b)
        input.text.delete(start, maxOf(a, b))
        input.setSelection(start)
    }
    private fun EditText.watch(change: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change(s.toString())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    internal fun popup(anchor: View, titles: List<String>, click: (Int) -> Unit) {
        PopupMenu(this, anchor).apply {
            titles.forEachIndexed { index, title -> menu.add(0, index, index, title) }
            setOnMenuItemClickListener { click(it.itemId); true }
            show()
        }
    }
    private fun hideKeyboard() {
        currentFocus?.windowToken?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }
    private fun duration(n: Int) = if (n >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", n / 3600, n / 60 % 60, n % 60)
        else String.format(java.util.Locale.ROOT, "%02d:%02d", n / 60, n % 60)
    internal fun message(value: String) { if (!isDestroyed) Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
}
