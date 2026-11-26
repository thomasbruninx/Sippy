package org.sippy

import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView

internal class LdapScreen(private val activity: MainActivity) {
    private val ui = activity.ui
    fun build() = ui.column(9).apply {
        val config = activity.prefs.ldap()
        if (config.host.isBlank()) {
            addView(ui.text("Configure your LDAP directory to search company contacts.", 15, Palette.secondary))
        }
        addView(ui.action("LDAP settings", "settings", fill = Palette.surface, outlined = true) {
            activity.settingsTab = 6
            activity.openLdapSettings()
        })
        val search = ui.field("Search LDAP name or number", activity.ldapQuery)
        addView(search)
        val status = ui.text("Search the directory, or leave the search empty to browse. Results stay separate from other contacts.", 13, Palette.secondary)
        val list = ListView(activity).apply { dividerHeight = ui.dp(5) }
        var contacts = emptyList<LdapContact>()
        // Use native themed text rows, including in dark mode.
        list.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1) {
            override fun getCount() = contacts.size
            override fun getItem(position: Int) = "${contacts[position].name}\n${contacts[position].number}"
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                ui.text(getItem(position).orEmpty(), 16).apply {
                    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                    background = ui.shape(Palette.surface, 12, Palette.border)
                }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            contacts[position].let { activity.confirmDial(it.number, it.name) }
        }
        val button = ui.action("Search LDAP", "contacts") {}
        button.isEnabled = config.host.isNotBlank()
        button.setOnClickListener {
            activity.ldapQuery = search.text.toString()
            button.isEnabled = false
            contacts = emptyList()
            (list.adapter as ArrayAdapter<*>).notifyDataSetChanged()
            status.text = "Searching LDAP…"
            val task = LdapTask(activity)
            activity.ldapTask?.close()
            activity.ldapTask = task
            task.search(config, search.text.toString()) { result ->
                button.isEnabled = true
                result.fold({ found ->
                    contacts = found.contacts
                    (list.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                    status.text = when {
                        found.limited -> "${contacts.size} numbers · result limit reached. Narrow your search."
                        contacts.isEmpty() -> "No matching LDAP contacts. Check the search and attribute settings."
                        else -> "${contacts.size} LDAP numbers"
                    }
                }, { status.text = LdapDirectory.error(it) })
                task.close()
            }
        }
        addView(button)
        addView(status)
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
