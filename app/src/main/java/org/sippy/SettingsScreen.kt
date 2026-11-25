package org.sippy

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch

internal class SettingsScreen(private val activity: MainActivity) {
    private val ui = activity.ui
    private val profile = activity.prefs.profile()
    private val textType = InputType.TYPE_CLASS_TEXT
    private val uriType = textType or InputType.TYPE_TEXT_VARIATION_URI
    private val passwordType = textType or InputType.TYPE_TEXT_VARIATION_PASSWORD

    fun build() = ui.column(9).apply {
        setPadding(ui.dp(17), ui.dp(12), ui.dp(17), ui.dp(9))
        addView(ui.row(8).apply {
            addView(ui.heading("Settings"), ui.weight())
            addView(ui.action("Event log", "history", click = activity::openEventLog))
        })
        val pages = listOf("Account", "Network", "Codecs", "Phonebook", "Sounds", "Application")
        activity.settingsTab = activity.settingsTab.coerceIn(pages.indices)
        addView(ui.labelled("Settings page", ui.row(8).apply {
            background = ui.ripple(Palette.surface, 12, Palette.border)
            minimumHeight = ui.dp(49)
            setPadding(ui.dp(13), 0, ui.dp(12), 0)
            addView(ui.text(pages[activity.settingsTab], 15, weight = true), ui.weight())
            addView(Symbol(activity, "down", Palette.accent), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            contentDescription = "Settings page: ${pages[activity.settingsTab]}"
            isFocusable = true
            setOnClickListener {
                activity.popup(this, pages) { selected ->
                    if (selected != activity.settingsTab) {
                        activity.settingsTab = selected
                        activity.renderPage()
                    }
                }
            }
        }))
        val content = ui.column(13).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) }
        when (activity.settingsTab) {
            0 -> {
                account(content)
                content.addView(ui.heading("SIP settings backup", "Export saved settings or restore them from a JSON file.").apply {
                    setPadding(0, ui.dp(15), 0, 0)
                })
                content.addView(ui.action("Export SIP settings", fill = Palette.surface, outlined = true,
                    click = activity.settingsFiles::export))
                content.addView(ui.action("Import SIP settings", fill = Palette.surface, outlined = true,
                    click = activity.settingsFiles::import))
            }
            1 -> network(content)
            2 -> codecs(content)
            3 -> phonebook(content)
            4 -> sounds(content)
            5 -> application(content)
        }
        addView(activity.scroll(content), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun application(content: LinearLayout) = with(content) {
        addView(ui.heading("Application", "Manage Sippy's application settings and permissions."))
        select("Appearance", Appearance.entries.map { it.label }, activity.prefs.appearance.ordinal) { selected ->
            activity.setAppearance(Appearance.entries[selected])
        }
        addView(ui.action("Check permissions", "settings", fill = Palette.surface,
            outlined = true, click = activity::checkPermissions))
        @Suppress("DEPRECATION")
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        addView(ui.column(6).apply {
            setPadding(0, ui.dp(15), 0, 0)
            addView(ui.text("Version $version", 16, weight = true))
            addView(ui.text("Made by Thomas Bruninx", 14, Palette.secondary))
        })
    }

    private fun sounds(content: LinearLayout) = with(content) {
        addView(ui.heading("Sounds", "Choose your incoming ringtone and dialpad sounds."))
        addView(toggle("Dialpad tones", activity.prefs.dialpadTones).apply {
            setOnCheckedChangeListener { _, enabled -> activity.prefs.dialpadTones = enabled }
        })
        addView(toggle("Vibrate on incoming calls", activity.prefs.vibrateOnIncoming).apply {
            setOnCheckedChangeListener { _, enabled -> activity.prefs.vibrateOnIncoming = enabled }
        })
        addView(ui.labelled("Incoming ringtone", ui.action(activity.settingsFiles.ringtoneLabel(), "volume",
            fill = Palette.surface, outlined = true, click = activity.settingsFiles::chooseRingtone)))
        val preview = ui.column()
        fun refreshPreview() {
            val playing = activity.settingsFiles.ringtonePreview.isPlaying
            preview.removeAllViews()
            preview.addView(ui.action(if (playing) "Pause preview" else "Play preview", if (playing) "hold" else "resume",
                fill = Palette.surface, outlined = true, click = activity.settingsFiles::toggleRingtonePreview))
        }
        activity.settingsFiles.ringtonePreview.onChange = ::refreshPreview
        refreshPreview()
        addView(preview)
    }

    private fun account(content: LinearLayout) = with(content) {
        addView(ui.heading("SIP account", "Connect an extension from your provider or PBX."))
        val user = addField("Username / extension", profile.username, uriType)
        val domain = addField("SIP domain", profile.domain, uriType, "pbx.example.com")
        val password = addField("Password", profile.password, passwordType).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        val register = toggle("Register with SIP server", profile.register)
        addView(register)
        val advanced = ui.column(12)
        advanced.visibility = View.GONE
        val advancedAction = ui.action("Advanced account settings", "down", Palette.surface, outlined = true) {
            advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        addView(advancedAction)
        addView(advanced)
        val auth = advanced.addField("Authentication ID", profile.authUser, uriType, "Defaults to username")
        val display = advanced.addField("Caller display name", profile.displayName)
        val registrar = advanced.addField("Registrar override", profile.server, uriType, "Hostname or host:port")
        val proxy = advanced.addField("Outbound proxy", profile.proxy, uriType, "Hostname or host:port")
        val realm = advanced.addField("Authentication realm", profile.realm)
        val expiry = advanced.addField("Registration expiry (seconds)", profile.expiry.toString(), InputType.TYPE_CLASS_NUMBER)
        addView(ui.action("Save and connect", "call", Palette.accent, Palette.onAccent) {
            saveSip {
                profile.copy(username = user.value(), domain = domain.value(), password = password.text.toString(),
                    authUser = auth.value(), displayName = display.value(), server = registrar.value(), proxy = proxy.value(),
                    realm = realm.value(), register = register.isChecked, expiry = expiry.number("registration expiry"))
            }
        })
    }

    private fun network(content: LinearLayout) = with(content) {
        addView(ui.heading("Network and media", "Configure transport, NAT traversal, and in-call signaling."))
        val transports = Transport.entries
        val transport = select("SIP transport", transports.map { it.name }, profile.transport.ordinal)
        val encryptions = Encryption.entries
        val encryption = select("Media encryption", encryptions.map { it.label }, profile.encryption.ordinal)
        val modes = Dtmf.entries
        val dtmf = select("DTMF method", modes.map { it.label }, profile.dtmf.ordinal)
        val stun = addField("STUN server", profile.stun, uriType, "stun.example.com:3478")
        val ice = toggle("Enable ICE", profile.ice)
        val echo = toggle("Echo cancellation", profile.echoCancellation)
        val ipv6 = toggle("Enable IPv6", profile.ipv6)
        addView(ice); addView(echo); addView(ipv6)
        val advanced = ui.column(12)
        advanced.visibility = View.GONE
        addView(ui.action("Local ports", "down", Palette.surface, outlined = true) {
            advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        addView(advanced)
        val sip = advanced.addField("Local SIP port (0 for automatic)", profile.localPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val rtp = advanced.addField("Local RTP port (0 for automatic)", profile.rtpPort.toString(), InputType.TYPE_CLASS_NUMBER)
        addView(ui.action("Save network settings", fill = Palette.accent, tint = Palette.onAccent) {
            saveSip(validateAccount = false) {
                profile.copy(transport = transports[transport()], encryption = encryptions[encryption()], dtmf = modes[dtmf()],
                    stun = stun.value(), ice = ice.isChecked, echoCancellation = echo.isChecked,
                    ipv6 = ipv6.isChecked, localPort = sip.number("SIP port"), rtpPort = rtp.number("RTP port"))
            }
        })
    }

    private fun saveSip(validateAccount: Boolean = true, build: () -> SipProfile) {
        if (activity.sipState.lines.isNotEmpty()) { activity.message("End all calls before changing SIP settings."); return }
        runCatching {
            val updated = build()
            updated.validate(requireAccount = validateAccount || updated.configured)
            activity.prefs.save(updated)
            activity.sip?.configure()
        }.onSuccess { activity.message("SIP settings saved.") }
            .onFailure { activity.message(it.localizedMessage ?: "Settings could not be saved.") }
    }

    private fun codecs(content: LinearLayout) = with(content) {
        addView(ui.heading("Audio codecs", "Enabled codecs are offered in priority order for new calls."))
        val list = ui.column(8)
        addView(list)
        fun render() {
            list.removeAllViews()
            val entries = activity.prefs.codecs()
            val available = activity.sipState.availableCodecs
            fun save(updated: List<Codec>) {
                if (activity.sipState.lines.isNotEmpty()) {
                    activity.message("End all calls before changing codecs.")
                    render()
                    return
                }
                if (available.none { id -> updated.any { it.id == id && it.enabled } }) {
                    activity.message("Keep at least one available codec enabled.")
                    render()
                    return
                }
                activity.prefs.saveCodecs(updated)
                runCatching { activity.sip?.applyCodecs() }.onFailure {
                    activity.prefs.saveCodecs(entries)
                    activity.message(it.localizedMessage ?: "Could not update codecs.")
                }
                render()
            }
            entries.forEachIndexed { index, item ->
                val usable = item.id in available
                val row = ui.row(5).apply {
                    ui.pad(this, 7)
                    background = ui.shape(Palette.surface, 12, Palette.border)
                    val enable = toggle(item.label, item.enabled && usable).apply {
                        isEnabled = usable
                        setOnCheckedChangeListener { _, checked ->
                            save(entries.map { if (it.id == item.id) it.copy(enabled = checked) else it })
                        }
                    }
                    addView(ui.column(3).apply {
                        addView(enable, LinearLayout.LayoutParams(-1, -2))
                        if (!usable) addView(ui.text(if (available.isEmpty()) "SDK unavailable" else "Requires a codec-enabled SDK",
                            11, Palette.secondary).apply { setPadding(ui.dp(4), 0, 0, ui.dp(3)) })
                    }, ui.weight())
                    fun move(offset: Int) {
                        val changed = entries.toMutableList()
                        changed.add(index + offset, changed.removeAt(index))
                        save(changed)
                    }
                    for ((offset, symbol) in listOf(-1 to "up", 1 to "down")) {
                        addView(ui.action("", symbol, Palette.surface, outlined = true) { move(offset) }.apply {
                            val direction = if (offset == -1) "up" else "down"
                            contentDescription = "Move ${item.label} $direction"
                            isEnabled = index + offset in entries.indices && usable
                            alpha = if (isEnabled) 1f else .38f
                        }, LinearLayout.LayoutParams(ui.dp(44), -2))
                    }
                }
                list.addView(row)
            }
        }
        render()
    }

    private fun phonebook(content: LinearLayout) = with(content) {
        addView(ui.heading("CSV phonebook", "Import a file or automatically sync a hosted directory."))
        addView(ui.action("Import a CSV file", "plus", Palette.surface, outlined = true, click = activity::importCsv))
        val config = activity.directory.config()
        val url = addField("CSV URL", config.url, uriType, "https://pbx.example.com/phonebook.csv")
        val interval = addField("Sync interval (minutes)", config.minutes.toString(), InputType.TYPE_CLASS_NUMBER)
        val status = ui.text(activity.directory.syncStatus(), 14, Palette.secondary)
        addView(status)
        activity.refreshSettingsStatus = { status.text = activity.directory.syncStatus() }
        addView(ui.action("Save sync settings", fill = Palette.accent, tint = Palette.onAccent) {
            runCatching {
                activity.directory.configure(url.value(), interval.number("sync interval"))
                activity.directory.schedule()
            }.onSuccess {
                activity.message("CSV settings saved.")
                if (url.value().isNotBlank()) activity.syncNow(showOutcome = false)
            }.onFailure { activity.message(it.localizedMessage ?: "Could not save sync settings.") }
        })
        addView(ui.action("Sync now", fill = Palette.surface, outlined = true) { activity.syncNow() })
        addView(ui.text("Use UTF-8 with a number column and an optional name column. Commas, semicolons, and tabs are supported.", 13, Palette.secondary))
    }

    private fun toggle(label: String, enabled: Boolean) = Switch(activity).apply {
        text = label
        setTextColor(Palette.text)
        textSize = 14f
        isChecked = enabled
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(44)
        setPadding(ui.dp(4), 0, ui.dp(3), 0)
        switchPadding = ui.dp(5)
    }

    private fun LinearLayout.select(label: String, values: List<String>, current: Int,
        onSelected: (Int) -> Unit = {}): () -> Int {
        var selection = current
        val box = ui.row(8).apply {
            background = ui.ripple(Palette.surface, 12, Palette.border)
            minimumHeight = ui.dp(49)
            setPadding(ui.dp(13), 0, ui.dp(12), 0)
        }
        val value = ui.text(values[current], 15, weight = true)
        box.addView(value, ui.weight())
        box.addView(Symbol(activity, "down", Palette.accent), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        box.isFocusable = true
        box.setOnClickListener {
            activity.popup(box, values) { selected ->
                selection = selected
                value.text = values[selected]
                onSelected(selected)
            }
        }
        addView(ui.labelled(label, box))
        return { selection }
    }

    private fun LinearLayout.addField(label: String, initial: String, type: Int = textType, hint: String = label): EditText =
        ui.field(hint, initial, type).also { addView(ui.labelled(label, it)) }

    private fun EditText.value() = text.toString().trim()
    private fun EditText.number(label: String) = value().toIntOrNull() ?: error("Enter a valid $label.")
}
