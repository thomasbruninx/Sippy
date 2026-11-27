package org.sippy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Android Enterprise AppConfig. Only the OS RestrictionsManager is a configuration source. */
internal object ManagedConfiguration {
    @Volatile var values: Map<String, Any?> = emptyMap()
        private set
    var error: String? = null
        private set
    var present = false
        private set
    private var suppliedKeys = emptySet<String>()
    private val observers = mutableSetOf<() -> Unit>()
    val blocksConnection get() = error != null && present && values.isEmpty()
    val status get() = when {
        error != null -> error!!
        present -> "Settings supplied by your organization (${values.keys.count { it !in setOf("password", "ldapPassword", "ringtoneName") }} managed values)."
        else -> "No managed configuration. Settings are controlled on this device."
    }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    fun isManaged(key: String): Boolean {
        val actual = when (key) { "passwordCipher" -> "password"; "ldapPasswordCipher" -> "ldapPassword"; else -> key }
        return values.containsKey(actual) || (error != null && present && actual in suppliedKeys)
    }
    fun refresh(context: Context) {
        val bundle = runCatching { context.getSystemService(RestrictionsManager::class.java)?.applicationRestrictions ?: Bundle.EMPTY }
            .getOrElse { return }
        apply(context, bundle)
    }
    /** Also used by device-side tests with synthetic restrictions; no intent can inject this bundle. */
    @Synchronized fun apply(context: Context, bundle: Bundle) {
        val before = Triple(values, error, present)
        val oldKeys = suppliedKeys
        try {
            @Suppress("DEPRECATION")
            if (bundle.getBoolean(android.os.UserManager.KEY_RESTRICTIONS_PENDING, false)) {
                present = true
                error = "Waiting for managed configuration from your organization."
                if (before != Triple(values, error, present)) observers.toList().forEach { runCatching(it) }
                return
            }
            val enabled = bundle.get("managedConfigurationEnabled")
            if (enabled != null && enabled !is Boolean) throw Invalid("managedConfigurationEnabled")
            present = enabled == true
            suppliedKeys = if (present) bundle.keySet().filter { it in types || it == "sipAccounts" }.map { if (it == "codecOrder") "codecs" else it }.toSet() else emptySet()
            val parsed = if (present) decode(context, bundle) else emptyMap()
            values = parsed
            error = null
            updateDirectory(context)
            if (before.first["eventRetentionDays"] != values["eventRetentionDays"]) ActivityStore.get(context).prune()
        } catch (failure: Invalid) {
            present = true
            error = "Managed configuration could not be applied (${failure.field}). Contact your administrator."
        } catch (_: Exception) {
            present = true
            error = "Managed configuration could not be applied. Contact your administrator."
        }
        if (before != Triple(values, error, present) || oldKeys != suppliedKeys) observers.toList().forEach { observer ->
            runCatching(observer).onFailure {
                ActivityStore.get(context).event(EventKind.WARNING, "A component could not apply managed settings. Open Sippy to retry.")
            }
        }
    }
    private class Invalid(val field: String) : IllegalArgumentException()
    @Suppress("DEPRECATION")
    private fun decode(context: Context, bundle: Bundle): Map<String, Any?> {
        if (bundle.containsKey("encryption")) throw Invalid("use sipAccounts.accountEncryption instead of encryption")
        val result = mutableMapOf<String, Any?>()
        for ((key, type) in types) if (bundle.containsKey(key)) {
            val value = bundle.get(key)
            if (!type.isInstance(value)) throw Invalid(key)
            if (value is String && (if (key == "csvContent") value.toByteArray(Charsets.UTF_8).size > 65536 else value.length > 16384)) throw Invalid(key)
            result[key] = value
        }
        if (bundle.containsKey("sipAccounts")) {
            val entries = bundle.get("sipAccounts") as? Array<*> ?: throw Invalid("sipAccounts")
            if (entries.size > 20) throw Invalid("sipAccounts")
            val accounts = entries.map { entry ->
                val item = entry as? Bundle ?: throw Invalid("sipAccounts")
                val id = item.get("id") as? String ?: throw Invalid("sipAccounts.id")
                if (!id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) throw Invalid("sipAccounts.id")
                val name = runCatching { SipAccountFields.name(item.get("name") as? String ?: "") }
                    .getOrElse { throw Invalid("sipAccounts.name") }
                val localCredentials = item.get("accountLocalCredentials") ?: false
                if (localCredentials !is Boolean) throw Invalid("sipAccounts.accountLocalCredentials")
                val credentialKeys = setOf("username", "authUser", "password")
                val fields = JSONObject()
                SipAccountFields.keys.filterNot { localCredentials && it in credentialKeys }.forEach { key ->
                    val nestedKey = "account" + key.replaceFirstChar { it.uppercase() }
                    if (item.containsKey(nestedKey)) {
                        val value = item.get(nestedKey)
                        if (accountTypes[key]?.isInstance(value) != true || value is String && value.length > 16384) throw Invalid("sipAccounts.$key")
                        fields.put(key, value)
                    }
                }
                val profile = runCatching { SipAccountFields.decode(fields) }
                    .getOrElse { throw Invalid("sipAccounts.settings") }
                val dndJson = JSONObject()
                DndSettings.keys.forEach { key ->
                    val nestedKey = "account" + key.replaceFirstChar { it.uppercase() }
                    if (item.containsKey(nestedKey)) {
                        val field = key.removePrefix("dnd").replaceFirstChar { it.lowercase() }
                        val value = item.get(nestedKey)
                        if (field in setOf("show", "reasonHeader", "priorityException") && value !is Boolean ||
                            field !in setOf("show", "reasonHeader", "priorityException") && value !is String) throw Invalid("sipAccounts.$key")
                        dndJson.put(field, value)
                    }
                }
                val dnd = runCatching { DndSettings.decode(dndJson) }.getOrElse { throw Invalid("sipAccounts.DND") }
                val optionsJson = JSONObject()
                if (item.containsKey("accountCodecOrder")) {
                    val order = item.get("accountCodecOrder") as? String ?: throw Invalid("sipAccounts.accountCodecOrder")
                    if (order.isNotBlank()) optionsJson.put("codecs", AccountOptions.codecJson(runCatching { AccountOptions.codecOrder(order) }.getOrElse { throw Invalid("sipAccounts.accountCodecOrder") }))
                }
                if (item.containsKey("accountRingtoneUri")) {
                    val uri = item.get("accountRingtoneUri") as? String ?: throw Invalid("sipAccounts.accountRingtoneUri")
                    optionsJson.put("ringtoneUri", uri.ifEmpty { null } ?: JSONObject.NULL)
                    optionsJson.put("ringtoneName", if (uri.isEmpty()) "Default ringtone" else "Managed ringtone")
                }
                if (item.containsKey("accountTransferMode")) optionsJson.put("transferMode", item.get("accountTransferMode"))
                val options = runCatching { AccountOptions.decode(optionsJson) }.getOrElse { throw Invalid("sipAccounts.options") }
                val optionKeys = optionsJson.keys().asSequence().toSet()
                val managedKeys = (SipAccountFields.keys + DndSettings.keys).filter { key ->
                    item.containsKey("account" + key.replaceFirstChar { it.uppercase() }) && !(localCredentials && key in credentialKeys)
                }.toSet() + optionKeys
                SipAccountConfig("managed:$id", name, profile, managed = true, dnd = dnd, managedKeys = managedKeys, options = options)
            }
            if (accounts.map { it.id }.distinct().size != accounts.size ||
                accounts.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size != accounts.size) throw Invalid("sipAccounts")
            val conflicts = accounts.filter { it.profile.configured }.groupBy { SipAccountFields.identity(it.profile) }
                .values.any { entries -> entries.map { SipAccountFields.credentials(it.profile) }.distinct().size > 1 }
            if (conflicts) throw Invalid("sipAccounts.credentials")
            result["sipAccounts"] = accounts
        }
        fun enum(key: String, choices: List<String>) {
            if (result.containsKey(key) && result[key] !in choices) throw Invalid(key)
        }
        enum("dtmf", Dtmf.entries.map { it.name }); enum("appearance", Appearance.entries.map { it.name })

        fun range(key: String, bounds: IntRange) { if (key in result && result[key] !in bounds) throw Invalid(key) }
        range("localPort", 0..65535); range("rtpPort", 0..65534)
        range("csvInterval", 15..10080); range("eventRetentionDays", 1..30); range("ldapPort", 1..65535)
        (result["csvContent"] as? String)?.takeIf { it.isNotBlank() }?.let {
            if (runCatching { parseCsv(it.byteInputStream(), Source.CSV) }.isFailure) throw Invalid("csvContent")
        }
        (result["csvUrl"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            if (runCatching { checkedUrl(it) }.isFailure) throw Invalid("csvUrl")
        }
        val local = Preferences(context, useManaged = false)
        fun s(key: String, fallback: String) = result[key] as? String ?: fallback
        fun n(key: String, fallback: Int) = result[key] as? Int ?: fallback
        val l = local.ldap()
        val ldap = l.copy(host = s("ldapHost", l.host), port = n("ldapPort", l.port),
            bindDn = s("ldapBindDn", l.bindDn), password = s("ldapPassword", l.password), baseDn = s("ldapBaseDn", l.baseDn),
            filter = s("ldapFilter", l.filter), nameAttributes = s("ldapNames", l.nameAttributes), phoneAttributes = s("ldapPhones", l.phoneAttributes))
        if (ldap.bindDn.any(Char::isISOControl)) throw Invalid("ldapBindDn")
        if (ldap.host.isNotEmpty() && runCatching { ldap.copy(bindDn = "", password = "").validate() }.isFailure) throw Invalid("LDAP settings")
        return result.toMap()
    }
    private fun updateDirectory(context: Context) {
        val local = Preferences(context, useManaged = false).data
        val managedUrl = values["csvUrl"] as? String
        val previous = local.getString("mdmCsvUrl", null) ?: local.getString("csvUrl", "").orEmpty()
        val current = managedUrl ?: local.getString("csvUrl", "").orEmpty()
        local.edit().apply {
            // A valid inline policy replaces the imported phonebook permanently.
            // Never let its previous personal contents return when management is removed.
            if (values.containsKey("csvContent")) remove("entries_CSV")
            if (managedUrl == null) remove("mdmCsvUrl") else putString("mdmCsvUrl", managedUrl)
            if (previous != current) {
                remove("entries_SYNC"); remove("lastSync"); remove("syncError")
                putInt("csvRevision", local.getInt("csvRevision", 0) + 1)
            }
        }.apply()
        runCatching { Directory(context).schedule() }.onFailure {
            ActivityStore.get(context).event(EventKind.WARNING, "Phonebook sync could not be scheduled after a managed configuration update.")
        }
    }

    private val accountTypes: Map<String, Class<*>> = mapOf(
        "username" to String::class.javaObjectType,
        "authUser" to String::class.javaObjectType,
        "password" to String::class.javaObjectType,
        "displayName" to String::class.javaObjectType,
        "domain" to String::class.javaObjectType,
        "server" to String::class.javaObjectType,
        "proxy" to String::class.javaObjectType,
        "realm" to String::class.javaObjectType,
        "transport" to String::class.javaObjectType,
        "register" to Boolean::class.javaObjectType,
        "expiry" to Int::class.javaObjectType,
        "stun" to String::class.javaObjectType,
        "ice" to Boolean::class.javaObjectType,
        "encryption" to String::class.javaObjectType,
    )

    private val types: Map<String, Class<*>> = mapOf(
        "doNotDisturb" to Boolean::class.javaObjectType,
        "localPort" to Int::class.javaObjectType,
        "rtpPort" to Int::class.javaObjectType,
        "dtmf" to String::class.javaObjectType,
        "echo" to Boolean::class.javaObjectType,
        "ipv6" to Boolean::class.javaObjectType,
        "appearance" to String::class.javaObjectType,
        "dialpadTones" to Boolean::class.javaObjectType,
        "vibrateOnIncoming" to Boolean::class.javaObjectType,
        "backgroundCalling" to Boolean::class.javaObjectType,
        "startOnBoot" to Boolean::class.javaObjectType,
        "eventRetentionDays" to Int::class.javaObjectType,
        "csvUrl" to String::class.javaObjectType,
        "csvContent" to String::class.javaObjectType,
        "csvInterval" to Int::class.javaObjectType,
        "ldapEnabled" to Boolean::class.javaObjectType,
        "ldapHost" to String::class.javaObjectType,
        "ldapPort" to Int::class.javaObjectType,
        "ldapTls" to Boolean::class.javaObjectType,
        "ldapBindDn" to String::class.javaObjectType,
        "ldapPassword" to String::class.javaObjectType,
        "ldapBaseDn" to String::class.javaObjectType,
        "ldapFilter" to String::class.javaObjectType,
        "ldapNames" to String::class.javaObjectType,
        "ldapPhones" to String::class.javaObjectType,
        "ldapClientCertificateAlias" to String::class.javaObjectType,    )
}

class SippyApplication : Application() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) ManagedConfiguration.refresh(context)
        }
    }
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        ManagedConfiguration.refresh(this)
    }
}
