package org.sippy

import org.json.JSONArray
import org.json.JSONObject

internal data class SipSettingsBackup(val profile: SipProfile, val codecs: List<Codec>, val name: String? = null, val dnd: DndSettings = DndSettings())

internal object SipSettingsJson {
    fun encode(profile: SipProfile, codecs: List<Codec>, includePassword: Boolean, name: String? = null, dnd: DndSettings = DndSettings()): String {
        val account = JSONObject().apply {
            put("username", profile.username); put("authUser", profile.authUser)
            if (includePassword) put("password", profile.password)
            put("displayName", profile.displayName); put("domain", profile.domain)
            put("server", profile.server); put("proxy", profile.proxy); put("realm", profile.realm)
            put("transport", profile.transport.name); put("register", profile.register)
            put("expiry", profile.expiry); put("localPort", profile.localPort); put("rtpPort", profile.rtpPort)
            put("encryption", profile.encryption.name); put("dtmf", profile.dtmf.name)
            put("stun", profile.stun); put("ice", profile.ice)
            put("echoCancellation", profile.echoCancellation); put("ipv6", profile.ipv6)
        }
        return JSONObject().put("format", "sippy-sip-settings").put("version", 1)
            .apply { if (name != null) put("name", SipAccountFields.name(name)) }
            .put("dnd", dnd.json()).put("account", account).put("codecs", JSONArray(codecs.map {
                JSONObject().put("id", it.id).put("enabled", it.enabled)
            })).toString(2)
    }

    fun decode(text: String, allowIncomplete: Boolean = false): SipSettingsBackup {
        val root = JSONObject(text)
        require(root.getString("format") == "sippy-sip-settings" && root.get("version") == 1) {
            "Unsupported Sippy settings format."
        }
        val a = root.getJSONObject("account")
        fun s(key: String) = (a.get(key) as? String) ?: error("Invalid $key.")
        fun b(key: String) = (a.get(key) as? Boolean) ?: error("Invalid $key.")
        fun n(key: String) = (a.get(key) as? Int) ?: error("Invalid $key.")
        val profile = SipProfile(
            username = s("username"), authUser = s("authUser"),
            password = if (a.has("password")) s("password") else "",
            displayName = s("displayName"), domain = s("domain"), server = s("server"),
            proxy = s("proxy"), realm = s("realm"), transport = Transport.valueOf(s("transport")),
            register = b("register"), expiry = n("expiry"), localPort = n("localPort"), rtpPort = n("rtpPort"),
            encryption = Encryption.valueOf(s("encryption")), dtmf = Dtmf.valueOf(s("dtmf")),
            stun = s("stun"), ice = b("ice"), echoCancellation = b("echoCancellation"), ipv6 = b("ipv6")
        )
        if (allowIncomplete) profile.copy(username = profile.username.ifEmpty { "sippy" }, domain = profile.domain.ifEmpty { "pbx.invalid" }).validate()
        else profile.validate(requireAccount = profile.username.isNotEmpty() || profile.domain.isNotEmpty())
        val items = root.getJSONArray("codecs")
        val codecs = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = item.get("id") as? String ?: error("Invalid codec.")
            val enabled = item.get("enabled") as? Boolean ?: error("Invalid codec.")
            val codec = Preferences.defaultCodecs.firstOrNull { it.id == id } ?: error("Unknown codec.")
            codec.copy(enabled = enabled)
        }
        require(codecs.map { it.id }.toSet().size == codecs.size && codecs.size == Preferences.defaultCodecs.size) {
            "Invalid codec list."
        }
        require(codecs.any { it.enabled }) { "Enable at least one codec." }
        val name = if (root.has("name")) SipAccountFields.name(root.get("name") as? String ?: error("Invalid account name.")) else null
        return SipSettingsBackup(profile, codecs, name, DndSettings.decode(root.optJSONObject("dnd")))
    }
}
