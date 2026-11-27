package org.sippy

import org.json.JSONObject

internal data class SipAccountConfig(val id: String, val name: String, val profile: SipProfile, val managed: Boolean = false, val dnd: DndSettings = DndSettings(), val managedKeys: Set<String> = emptySet(), val options: AccountOptions = AccountOptions())

/** Identity, registration, transport, NAT and media encryption belong to each account. */
internal object SipAccountFields {
    val keys = setOf("username", "authUser", "password", "displayName", "domain", "server", "proxy", "realm", "transport", "register", "expiry", "stun", "ice", "encryption")
    fun encode(profile: SipProfile): JSONObject {
        val all = JSONObject(SipSettingsJson.encode(profile, Preferences.defaultCodecs, true)).getJSONObject("account")
        return JSONObject().apply { keys.forEach { put(it, all.get(it)) } }
    }
    fun decode(fields: JSONObject, shared: SipProfile = SipProfile()): SipProfile {
        val root = JSONObject(SipSettingsJson.encode(shared, Preferences.defaultCodecs, true))
        val account = root.getJSONObject("account")
        keys.forEach { if (fields.has(it)) account.put(it, fields.get(it)) }
        return SipSettingsJson.decode(root.toString(), allowIncomplete = true).profile
    }
    fun identity(profile: SipProfile): String? = if (profile.configured) "${profile.username}@${profile.identityDomain.lowercase(java.util.Locale.ROOT)}" else null
    fun credentials(profile: SipProfile) = (profile.authUser.ifBlank { profile.username }) to profile.password
    fun name(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl)) { "Enter an account name of 1–80 characters." }
    }
}
