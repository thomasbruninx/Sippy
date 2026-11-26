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
    fun apply(context: Context, bundle: Bundle) {
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
            suppliedKeys = if (present) bundle.keySet().filter { it in types }.map { if (it == "codecOrder") "codecs" else it }.toSet() else emptySet()
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
        val result = mutableMapOf<String, Any?>()
        for ((key, type) in types) if (bundle.containsKey(key)) {
            val value = bundle.get(key)
            if (!type.isInstance(value)) throw Invalid(key)
            if (value is String && value.length > 16384) throw Invalid(key)
            result[key] = value
        }
        fun enum(key: String, choices: List<String>) {
            if (result.containsKey(key) && result[key] !in choices) throw Invalid(key)
        }
        enum("transport", Transport.entries.map { it.name }); enum("encryption", Encryption.entries.map { it.name })
        enum("dtmf", Dtmf.entries.map { it.name }); enum("appearance", Appearance.entries.map { it.name })
        enum("transferMode", TransferMode.entries.map { it.name })
        fun range(key: String, bounds: IntRange) { if (key in result && result[key] !in bounds) throw Invalid(key) }
        range("expiry", 60..86400); range("localPort", 0..65535); range("rtpPort", 0..65534)
        range("csvInterval", 15..10080); range("eventRetentionDays", 1..30); range("ldapPort", 1..65535)
        for (key in listOf("domain", "server", "proxy", "stun")) {
            val value = result[key] as? String ?: continue
            if (value.isNotEmpty()) {
                val uri = runCatching { URI("https://$value") }.getOrNull()
                if (uri?.host == null || uri.rawUserInfo != null || uri.rawPath.isNotEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
                    uri.port != -1 && uri.port !in 1..65535) throw Invalid(key)
            }
        }
        (result["username"] as? String)?.let { if (it.isNotEmpty() && !it.matches(Regex("[A-Za-z0-9_.+!~*'%-]+"))) throw Invalid("username") }
        (result.remove("codecOrder") as? String)?.takeIf { it.isNotBlank() }?.let { order ->
            val ids = order.split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }
            if (ids.distinct().size != ids.size || ids.any { id -> Preferences.defaultCodecs.none { it.id == id } }) throw Invalid("codecOrder")
            val codecs = ids.map { id -> Preferences.defaultCodecs.first { it.id == id }.copy(enabled = true) } +
                Preferences.defaultCodecs.filter { it.id !in ids }.map { it.copy(enabled = false) }
            result["codecs"] = JSONArray(codecs.map { JSONObject().put("id", it.id).put("enabled", it.enabled) }).toString()
        }
        (result["csvUrl"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            if (runCatching { checkedUrl(it) }.isFailure) throw Invalid("csvUrl")
        }
        if ("ringtoneUri" in result) {
            val text = result["ringtoneUri"] as String
            if (text.isEmpty()) result["ringtoneUri"] = null
            else if (runCatching { URI(text).scheme in setOf("content", "android.resource", "file") }.getOrDefault(false).not()) throw Invalid("ringtoneUri")
            result["ringtoneName"] = if (text.isEmpty()) "Default ringtone" else "Managed ringtone"
        }
        val local = Preferences(context, useManaged = false)
        val p = local.profile()
        fun s(key: String, fallback: String) = result[key] as? String ?: fallback
        fun n(key: String, fallback: Int) = result[key] as? Int ?: fallback
        val sip = p.copy(username = s("username", p.username), domain = s("domain", p.domain), server = s("server", p.server),
            proxy = s("proxy", p.proxy), stun = s("stun", p.stun), expiry = n("expiry", p.expiry),
            localPort = n("localPort", p.localPort), rtpPort = n("rtpPort", p.rtpPort))
        if (runCatching { sip.validate(requireAccount = sip.configured) }.isFailure) throw Invalid("SIP account/network")
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

    private val types: Map<String, Class<*>> = mapOf(
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
        "localPort" to Int::class.javaObjectType,
        "rtpPort" to Int::class.javaObjectType,
        "encryption" to String::class.javaObjectType,
        "dtmf" to String::class.javaObjectType,
        "stun" to String::class.javaObjectType,
        "ice" to Boolean::class.javaObjectType,
        "echo" to Boolean::class.javaObjectType,
        "ipv6" to Boolean::class.javaObjectType,
        "codecOrder" to String::class.javaObjectType,
        "appearance" to String::class.javaObjectType,
        "transferMode" to String::class.javaObjectType,
        "dialpadTones" to Boolean::class.javaObjectType,
        "vibrateOnIncoming" to Boolean::class.javaObjectType,
        "ringtoneUri" to String::class.javaObjectType,
        "backgroundCalling" to Boolean::class.javaObjectType,
        "startOnBoot" to Boolean::class.javaObjectType,
        "eventRetentionDays" to Int::class.javaObjectType,
        "csvUrl" to String::class.javaObjectType,
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
