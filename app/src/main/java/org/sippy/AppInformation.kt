package org.sippy

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

internal object AppInformation {
    private const val github = "https://github.com/thomasbruninx/Sippy"

    fun openGitHub(activity: MainActivity) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(github))) }
            .onFailure { activity.message("No app is available to open GitHub. Visit $github") }
    }

    fun showLicense(activity: MainActivity) {
        val ui = activity.ui
        val documents = linkedMapOf(
            "Sippy license" to "LICENSE.md",
            "Third-party libraries" to "THIRD_PARTY_NOTICES.md",
            "Apache License 2.0" to "licenses/APACHE-2.0.txt",
            "Linphone — AGPLv3" to "licenses/LINPHONE-AGPL-3.0.txt",
            "JVM library notices" to "licenses/JVM-NOTICES.txt",
            "libsrtp — BSD" to "licenses/SRTP-BSD.txt",
            "JsonCpp — MIT / public domain" to "licenses/JSONCPP-MIT.txt",
            "libjpeg-turbo — licenses" to "licenses/JPEG-LICENSE.md",
            "JPEG — IJG license" to "licenses/JPEG-IJG.txt",
            "Opus — BSD" to "licenses/OPUS-BSD.txt",
            "Speex — BSD" to "licenses/SPEEX-BSD.txt",
            "WebRTC — BSD" to "licenses/WEBRTC-BSD.txt",
            "LLVM libc++ — license" to "licenses/LLVM-LICENSE.txt"
        )
        val text = ui.text("", 14).apply {
            setTextIsSelectable(true)
            setLinkTextColor(Palette.accent)
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20))
        }
        val scroll = activity.scroll(text)
        val content = ui.column(8).apply {
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.text("Sippy: GPLv3. Linphone: AGPLv3. UnboundID, AndroidX, Kotlin and other JVM libraries: Apache 2.0. See Third-party libraries for details.", 13, Palette.secondary).apply {
                setPadding(ui.dp(20), 0, ui.dp(20), 0)
            })
            addView(Spinner(activity).apply {
                contentDescription = "License document"
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, documents.keys.toList())
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        text.text = activity.assets.open("legal/${documents.values.elementAt(position)}").bufferedReader().use { it.readText() }
                        Linkify.addLinks(text, java.util.regex.Pattern.compile("https?://[^\\s]+"), null)
                        scroll.scrollTo(0, 0)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            })
            addView(scroll, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        AlertDialog.Builder(activity).setTitle("License")
            .setView(content).setPositiveButton("Close", null)
            .show().apply {
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * .85f).toInt())
            }
    }
}
