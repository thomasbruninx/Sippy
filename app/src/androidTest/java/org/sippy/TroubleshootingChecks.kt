package org.sippy

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise export and the real confirmed reset with an active managed account and directory. */
internal class TroubleshootingChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
    private fun <T> read(action: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var value: Result<T>? = null
        action { value = it; latch.countDown() }
        check(latch.await(20, TimeUnit.SECONDS)) { "Storage operation timed out" }
        return checkNotNull(value).getOrThrow()
    }
    private fun until(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(70) }
        error(message)
    }
    private fun click(text: String) {
        var button: AccessibilityNodeInfo? = null
        until("Missing dialog button: $text") {
            button = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString().equals(text, true) && it.isClickable }
            button != null
        }
        check(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    fun run() {
        val context = instrumentation.targetContext
        val prefs = Preferences(context)
        val snapshot = prefs.localSnapshot()
        val savedTones = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("ringtone-") }.associateWith { it.readBytes() }
        val app = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ActivityStore.get(context)
        val personalTone = File.createTempFile("ringtone-", ".wav", context.filesDir)
        val managedTone = File.createTempFile("ringtone-", ".wav", context.filesDir)
        try {
            instrumentation.context.assets.open("sippy-tone.wav").use { input -> personalTone.outputStream().use { input.copyTo(it) } }
            personalTone.copyTo(managedTone, overwrite = true)
            until("SIP service did not bind") { main { app.sip != null } }
            val account = Bundle().apply {
                putString("id", "reset-test"); putString("name", "Managed reset account")
                putBoolean("accountLocalCredentials", true); putString("accountDomain", "127.0.0.2")
                putBoolean("accountRegister", false); putString("accountRingtoneUri", Uri.fromFile(managedTone).toString())
                putString("accountTransferMode", "DIRECT_ONLY"); putString("accountCodecOrder", "pcmu")
            }
            val policy = Bundle().apply {
                putBoolean("managedConfigurationEnabled", true)
                putParcelableArray("sipAccounts", arrayOf(account))
                putString("appearance", "LIGHT")
                putString("csvContent", "name,number\r\n\"Renée, Front desk\",101\r\nOffice,102\r\n")
                putString("csvUrl", "https://example.com/managed.csv")
            }
            main {
                prefs.resetLocalSettings()
                prefs.data.edit().putBoolean("firstRunPermissionsChecked", true).commit()
                prefs.save(SipProfile(username="200",domain="127.0.0.1",register=false,password="RESET_PERSONAL_SECRET"))
                prefs.saveOptions(AccountOptions(ringtoneUri=Uri.fromFile(personalTone).toString(),ringtoneName="Personal tone"))
                app.directory.saveLocal(null,"Local reset contact","300")
                app.directory.import("name,number\nImported reset contact,301\n".byteInputStream())
                prefs.data.edit().putString("entries_SYNC","[]").putString("lastDialedNumber","555").commit()
                ManagedConfiguration.apply(context, policy)
                val managed = prefs.accounts().first { it.managed }
                prefs.selectAccount(managed.id)
                prefs.save(prefs.profile().copy(username="201",password="RESET_TEMPLATE_SECRET",authUser="local-auth"))
                app.sip!!.configure()
            }
            check(app.directory.stored(Source.CSV).map { it.number } == listOf("101","102"))
            check(!prefs.localSnapshot().containsKey("entries_CSV")) { "Old imported phonebook was retained" }
            check(app.directory.stored(Source.LOCAL).size == 1)
            check(runCatching { app.directory.import("name,number\nOverride,999\n".byteInputStream()) }.isFailure) { "Managed phonebook allowed a local import" }
            main { ManagedConfiguration.apply(context, Bundle(policy).apply { putString("csvContent", "name,number\nReplacement,103\n") }) }
            check(app.directory.stored(Source.CSV).map { it.number } == listOf("103")) { "Managed update appended instead of replacing" }
            main { ManagedConfiguration.apply(context, policy) }
            main { ManagedConfiguration.apply(context, Bundle(policy).apply { putString("csvContent","name,number\nBad,not a number\n") }) }
            check(ManagedConfiguration.error != null)
            check(app.directory.stored(Source.CSV).size == 2) { "Invalid CSV replaced the valid managed directory" }
            main { ManagedConfiguration.apply(context, Bundle(policy).apply { putString("csvContent", "name,number\n" + "é".repeat(32768) + ",101\n") }) }
            check(ManagedConfiguration.error != null && app.directory.stored(Source.CSV).size == 2) { "Oversized UTF-8 CSV was accepted" }
            main { ManagedConfiguration.apply(context, policy) }
            // Export includes all retained events, proper quoting/newlines and redaction.
            val marker = "CSV reset marker, \"quoted\"\nSecond line"
            store.event(EventKind.INFO, marker)
            store.event(EventKind.WARNING, "=HYPERLINK(\"https://example.com\")")
            store.event(EventKind.ERROR, "Never export RESET_SECRET", "RESET_SECRET")
            repeat(550) { store.event(EventKind.INFO,"Export event $it") }
            val bytes = ByteArrayOutputStream()
            val count = read<Int> { store.exportEvents({ bytes }, it) }
            val csv = bytes.toString("UTF-8")
            check(count >= 553 && csv.startsWith("timestamp_utc,level,message\r\n"))
            check(csv.contains("CSV reset marker, \"\"quoted\"\"\nSecond line"))
            check(csv.contains("\"'=HYPERLINK(") && !csv.contains("RESET_SECRET"))
            store.record(HistoryRecord("reset-history",System.currentTimeMillis(),false,CallOutcome.ANSWERED,"Reset call","200",3))
            read<List<HistoryRecord>> { store.history(100,it) }.also { check(it.any { row -> row.id=="reset-history" }) }
            main { app.confirmFactoryReset() }
            click("Cancel")
            check(prefs.accounts().any { !it.managed && it.profile.password=="RESET_PERSONAL_SECRET" })
            check(app.directory.stored(Source.LOCAL).isNotEmpty())
            main { app.confirmFactoryReset() }
            click("Factory reset")
            until("Reset did not complete") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Factory reset complete")?.isNotEmpty()==true
            }
            check(read<List<HistoryRecord>> { store.history(100,it) }.isEmpty())
            check(read<List<EventRecord>> { store.events(null,10000,it) }.none { it.message.contains("CSV reset marker") })
            check(app.directory.stored(Source.LOCAL).isEmpty())
            check(app.directory.stored(Source.CSV).size==2 && prefs.data.getString("csvUrl","")=="https://example.com/managed.csv")
            check(prefs.accounts().single().managed)
            check(prefs.profile().username.isEmpty() && prefs.profile().password.isEmpty() && prefs.profile().authUser.isEmpty())
            check(prefs.profile().domain=="127.0.0.2" && prefs.transferMode==TransferMode.DIRECT_ONLY)
            check(prefs.codecs().filter { it.enabled }.map { it.id }==listOf("pcmu"))
            check(!personalTone.exists() && managedTone.exists())
            check(prefs.localSnapshot().values.none { it.toString().contains("RESET_PERSONAL_SECRET") || it.toString().contains("RESET_TEMPLATE_SECRET") })
            click("Close")
            main { app.sip!!.configure() }
            check(read<List<HistoryRecord>> { store.history(100,it) }.isEmpty()) { "SDK replay restored deleted history" }
            main { ManagedConfiguration.apply(context,Bundle(policy).apply { putString("csvContent", "") }) }
            check(app.directory.stored(Source.CSV).isEmpty()) { "Blank CSV did not clear imported contacts" }
            main { ManagedConfiguration.apply(context,Bundle.EMPTY) }
            check(app.directory.stored(Source.CSV).isEmpty()) { "Old imports returned after policy removal" }
            check(prefs.selectedAccount().name=="Default account" && !prefs.profile().configured)
        } finally {
            main {
                ManagedConfiguration.apply(context,Bundle.EMPTY)
                prefs.restoreSnapshot(snapshot)
                app.sip?.configure()
                app.finish()
            }
            personalTone.delete(); managedTone.delete()
            savedTones.forEach { (file, bytes) -> file.writeBytes(bytes) }
        }
    }
}
