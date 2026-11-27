package org.sippy

import android.app.AlertDialog
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import java.text.DateFormat
import java.util.Date

internal class ActivityScreens(private val activity: MainActivity) {
    private val ui = activity.ui
    private val store = ActivityStore.get(activity)
    private val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    fun history(redial: (String, String) -> Unit): View {
        val page = listPage<HistoryRecord>("Call history", "Recent incoming and outgoing calls", "No calls recorded yet.",
            { limit, done -> store.history(limit, done) }) { call ->
            val tint = if (call.outcome in setOf(CallOutcome.MISSED, CallOutcome.FAILED)) Palette.danger else Palette.accent
            ui.row(9).apply {
                background = ui.shape(Palette.surface, 14, Palette.border)
                ui.pad(this, 10)
                val direction = if (call.incoming) "Incoming" else "Outgoing"
                addView(Symbol(activity, if (call.incoming) "incoming" else "outgoing", tint),
                    LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                addView(ui.column(5).apply {
                    addView(ui.text(call.name, 16, weight = true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(ui.text("$direction · ${call.outcome.label}", 13, tint, true))
                    val duration = if (call.seconds > 0) " · ${call.seconds / 60}m ${call.seconds % 60}s" else ""
                    addView(ui.text(date.format(Date(call.time)) + duration, 12, Palette.secondary))
                }, ui.weight())
                if (dialTarget(call.address) != null) {
                    addView(ui.action("Redial", "call") { redial(call.address, call.name) })
                }
            }
        }
        val heading = page.root.getChildAt(0)
        page.root.removeView(heading)
        val clear = ui.action("Clear history", fill = Palette.surface, outlined = true) {}
        clear.setOnClickListener {
            AlertDialog.Builder(activity).setTitle("Clear call history?")
                .setMessage("Permanently delete all saved call history? This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear history") { _, _ ->
                    clear.isEnabled = false
                    store.clearHistory { result ->
                        if (!activity.isDestroyed) {
                            clear.isEnabled = true
                            result.onSuccess {
                                if (page.root.isAttachedToWindow) page.reset()
                                activity.message("Call history cleared.")
                            }.onFailure { activity.message("Could not clear call history. Please try again.") }
                        }
                    }
                }.show()
        }
        page.root.addView(ui.row(8).apply {
            gravity = Gravity.TOP
            addView(heading, ui.weight())
            addView(clear)
        }, 0)
        return page.root
    }

    fun events(): View {
        var filter: EventKind? = null
        val page = listPage<EventRecord>("Event log", "SIP signaling and diagnostics", "No events match this filter.",
            { limit, done -> store.events(filter, limit, done) }, headingScale = .8f) { event ->
            val tint = when (event.kind) {
                EventKind.ERROR -> Palette.danger
                EventKind.WARNING -> Palette.warning
                else -> Palette.accent
            }
            val summary = ui.column(6).apply {
                background = ui.ripple(Palette.surface, 14, Palette.border)
                ui.pad(this, 12)
                addView(ui.text("${event.kind.label} · ${date.format(Date(event.time))}", 12, tint, true))
                addView(ui.text(event.message.lineSequence().first(), 14).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                })
                setOnClickListener {
                    val text = ui.text(event.message, 14).apply {
                        setTextIsSelectable(true)
                        ui.pad(this, 18)
                    }
                    AlertDialog.Builder(activity).setTitle(event.kind.label).setView(activity.scroll(text))
                        .setPositiveButton("Close", null).show()
                }
            }
            summary
        }
        val controls = ui.row(8)
        lateinit var filterButton: View
        lateinit var retentionButton: View
        fun updateControls() {
            controls.removeAllViews()
            filterButton = ui.action(filter?.label ?: "All events", "down", fill = Palette.surface, outlined = true) {
                val choices = listOf<EventKind?>(null) + EventKind.entries
                activity.popup(filterButton, choices.map { it?.label ?: "All events" }) {
                    filter = choices[it]
                    updateControls()
                    page.reset()
                }
            }
            retentionButton = ui.action("Keep ${store.retentionDays} days", "settings") {
                val input = ui.field("Days (1–30)", store.retentionDays.toString(), InputType.TYPE_CLASS_NUMBER)
                val fields = ui.column().apply { ui.pad(this, 18); addView(input) }
                val dialog = AlertDialog.Builder(activity).setTitle("Event log retention").setView(fields)
                    .setMessage("Events older than this limit are automatically removed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Save", null).show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val days = input.text.toString().toIntOrNull()
                    if (days == null || days !in 1..30) {
                        input.error = "Enter a number from 1 to 30."
                    } else {
                        store.setRetention(days)
                        dialog.dismiss()
                        updateControls()
                        page.reset()
                    }
                }
            }
            controls.addView(filterButton, ui.weight())
            retentionButton.isEnabled = !activity.prefs.isManaged("eventRetentionDays")
            if (!retentionButton.isEnabled) {
                retentionButton.alpha = .6f
                retentionButton.contentDescription = "Event retention managed by your organization"
            }
            controls.addView(retentionButton, ui.weight())
        }
        updateControls()
        page.root.addView(controls, 1)
        return page.root
    }

    private data class ListPage(val root: LinearLayout, val reset: () -> Unit)

    private fun <T> listPage(title: String, subtitle: String, emptyText: String,
        read: (Int, (Result<List<T>>) -> Unit) -> Unit, headingScale: Float = 1f, render: (T) -> View): ListPage {
        val root = ui.column(9).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            addView(ui.heading(title, subtitle, titleScale = headingScale))
        }
        var rows = emptyList<T>()
        val list = ListView(activity).apply {
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = ui.dp(6)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) = render(rows[position])
        }
        list.adapter = adapter
        val empty = ui.text("Loading…", 15, Palette.secondary).apply { gravity = Gravity.CENTER; ui.pad(this, 12) }
        root.addView(FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        var limit = 100
        var generation = 0
        lateinit var refresh: () -> Unit
        val more = ui.action("Load older entries", "down", fill = Palette.surface, outlined = true) {
            limit += 100
            refresh()
        }.apply { visibility = View.GONE }
        root.addView(more)
        refresh = {
            val request = ++generation
            read(limit + 1) { result ->
                if (request != generation || !root.isAttachedToWindow) return@read
                result.onSuccess { records ->
                    val displayed = records.take(limit)
                    if (rows != displayed) { rows = displayed; adapter.notifyDataSetChanged() }
                    empty.text = emptyText
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    more.visibility = if (records.size > limit) View.VISIBLE else View.GONE
                }.onFailure {
                    empty.text = "Could not read saved activity."
                    empty.visibility = View.VISIBLE
                    more.visibility = View.GONE
                }
            }
        }
        val poll = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow) return
                refresh()
                root.postDelayed(this, 2500)
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.post(poll) }
            override fun onViewDetachedFromWindow(v: View) { root.removeCallbacks(poll); generation++ }
        })
        return ListPage(root) {
            limit = 100
            list.setSelection(0)
            refresh()
        }
    }
}
