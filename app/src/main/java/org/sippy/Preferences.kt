package org.sippy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class Transport { UDP, TCP, TLS }
internal enum class Encryption(val label: String) {
    None("None (RTP)"), SRTP("SRTP (SDES)"), ZRTP("ZRTP"), DTLS("DTLS-SRTP")
}
internal enum class Dtmf(val label: String) { RTP("RTP events (RFC 4733)"), INFO("SIP INFO") }
internal enum class TransferMode(val label: String) {
    DIRECT_ONLY("Direct only"), ATTENDED_ONLY("Attended only"), BOTH("Both")
}
internal enum class Appearance(val label: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("Follow system") }

internal data class SipProfile(
    val username: String = "",
    val authUser: String = "",
    val password: String = "",
    val displayName: String = "",
    val domain: String = "",
    val server: String = "",
    val proxy: String = "",
    val realm: String = "",
    val transport: Transport = Transport.UDP,
    val register: Boolean = true,
    val expiry: Int = 600,
    val localPort: Int = 0,
    val rtpPort: Int = 0,
    val encryption: Encryption = Encryption.None,
    val dtmf: Dtmf = Dtmf.RTP,
    val stun: String = "",
    val ice: Boolean = false,
    val echoCancellation: Boolean = true,
    val ipv6: Boolean = true,
) {
    val configured get() = username.isNotBlank() && domain.isNotBlank()
    val identityDomain: String get() = java.net.URI("sip://$domain").host
    val registrar: String get() = server.ifBlank { domain }

    fun validate(requireAccount: Boolean = true) {
        require(!requireAccount || username.matches(Regex("[A-Za-z0-9_.+!~*'%-]+"))) { "Enter a valid SIP username." }
        require(!requireAccount || validEndpoint(domain)) { "Enter a SIP domain or IP address without a protocol." }
        require(listOf(server, proxy, stun).all { it.isEmpty() || validEndpoint(it) }) {
            "Server addresses must be hostnames or IP addresses with an optional port."
        }
        require(expiry in 60..86400) { "Registration expiry must be 60–86400 seconds." }
        require(localPort in 0..65535 && rtpPort in 0..65534) { "Enter valid local SIP and RTP ports." }
    }

    private fun validEndpoint(value: String): Boolean = runCatching {
        val uri = java.net.URI("https://$value")
        uri.host != null && uri.rawUserInfo == null && uri.rawPath.isEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)
}

internal data class Codec(val id: String, val label: String, val enabled: Boolean)

internal class Preferences(private val context: Context, private val useManaged: Boolean = true) {
    private val decryptedSecrets = mutableMapOf<String, String>()
    private var accountCacheKey: List<Any?>? = null
    private var accountCache = emptyList<SipAccountConfig>()
    private val local = context.getSharedPreferences("sippy", Context.MODE_PRIVATE)
    val data = if (useManaged) ManagedPreferences(local) else local
    fun isManaged(key: String): Boolean {
        if (!useManaged) return false
        val accountField = key in SipAccountFields.keys || key in DndSettings.keys
        return if (accountField) key in selectedAccount().managedKeys else ManagedConfiguration.isManaged(key)
    }
    private fun accountOverrides() = JSONObject(local.getString("sipAccountOverrides", "{}").orEmpty())

    var doNotDisturb: Boolean
        get() = dndEnabled(selectedAccount().id)
        set(value) { setDndEnabled(selectedAccount().id, value) }

    fun dndEnabled(id: String): Boolean {
        val account = accounts().firstOrNull { it.id == id } ?: return false
        if (!account.dnd.show) return false
        if (useManaged && ManagedConfiguration.isManaged("doNotDisturb")) return data.getBoolean("doNotDisturb", false)
        return local.getBoolean("dndState.$id", local.getBoolean("doNotDisturb", false))
    }
    fun setDndEnabled(id: String, enabled: Boolean): Boolean {
        val account = accounts().firstOrNull { it.id == id } ?: return false
        if (useManaged && ManagedConfiguration.isManaged("doNotDisturb") || enabled && !account.dnd.show) return false
        local.edit().putBoolean("dndState.$id", enabled).apply()
        return true
    }

    private fun localAccounts(): JSONArray {
        local.getString("sipAccountsLocal", null)?.let { return JSONArray(it) }
        // Migrate the existing account without decrypting or copying its password as plaintext.
        val legacy = if (useManaged) Preferences(context, useManaged = false).flatProfile() else flatProfile()
        val fields = SipAccountFields.encode(legacy).apply {
            remove("password")
            local.getString("passwordCipher", null)?.let { put("passwordCipher", it) }
        }
        val initial = JSONArray().put(JSONObject().put("id", "default").put("name", "Default account").put("fields", fields))
        check(local.edit().putString("sipAccountsLocal", initial.toString()).commit()) { "Could not save accounts." }
        return initial
    }

    fun accounts(): List<SipAccountConfig> {
        val shared = flatProfile()
        @Suppress("UNCHECKED_CAST")
        val managed = if (useManaged) ManagedConfiguration.values["sipAccounts"] as? List<SipAccountConfig> ?: emptyList() else emptyList()
        val stored = localAccounts()
        val overrideJson = accountOverrides()
        val cacheKey = listOf(stored.toString(), shared, managed, overrideJson.toString())
        if (cacheKey == accountCacheKey) return accountCache
        val personal = (0 until stored.length()).map { index ->
            val item = stored.getJSONObject(index)
            val fields = JSONObject(item.getJSONObject("fields").toString())
            fields.put("password", fields.optString("passwordCipher", "").takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty())
            SipAccountConfig(item.getString("id"), item.getString("name"), SipAccountFields.decode(fields, shared), dnd = DndSettings.decode(fields.optJSONObject("dnd")))
        }
        val visible = if (managed.isEmpty()) personal else personal.filter { it.profile.configured || it.id != "default" }
        val effectiveManaged = managed.map { account ->
            val overrides = overrideJson.optJSONObject(account.id) ?: JSONObject()
            val fields = SipAccountFields.encode(account.profile)
            SipAccountFields.keys.filter { it !in account.managedKeys && it != "password" }.forEach { key ->
                if (overrides.has(key)) fields.put(key, overrides.get(key))
            }
            if ("password" !in account.managedKeys) fields.put("password", overrides.optString("passwordCipher").takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty())
            val dnd = account.dnd.json()
            DndSettings.keys.filter { it !in account.managedKeys }.forEach { key ->
                val field = key.removePrefix("dnd").replaceFirstChar { it.lowercase() }
                overrides.optJSONObject("dnd")?.let { if (it.has(field)) dnd.put(field, it.get(field)) }
            }
            account.copy(profile = SipAccountFields.decode(fields, shared), dnd = DndSettings.decode(dnd))
        }
        accountCache = effectiveManaged + visible.filter { personal ->
            val identity = SipAccountFields.identity(personal.profile)
            identity == null || effectiveManaged.none { SipAccountFields.identity(it.profile) == identity }
        }
        accountCacheKey = cacheKey
        return accountCache
    }

    fun selectedAccount(): SipAccountConfig {
        val list = accounts()
        return list.firstOrNull { it.id == local.getString("selectedAccountId", null) }
            ?: list.firstOrNull { it.id == local.getString("lastPersonalAccountId", null) } ?: list.first()
    }
    fun selectAccount(id: String) {
        val account = requireNotNull(accounts().firstOrNull { it.id == id }) { "Account no longer exists." }
        val edit = local.edit().putString("selectedAccountId", id)
        if (!account.managed) edit.putString("lastPersonalAccountId", id)
        check(edit.commit()) { "Could not select account." }
    }
    private fun validateAccountIdentity(profile: SipProfile, id: String? = null, managed: Boolean = false) {
        val identity = SipAccountFields.identity(profile) ?: return
        val same = accounts().filter { it.id != id && SipAccountFields.identity(it.profile) == identity }
        require(managed || same.none { it.managed }) { "This SIP identity is managed by your organization." }
        require(same.filter { !managed || it.managed }.all { SipAccountFields.credentials(it.profile) == SipAccountFields.credentials(profile) }) {
            "This SIP identity is already stored with different credentials. Update that account or use a different SIP username or domain."
        }
    }

    fun addAccount(name: String, profile: SipProfile = SipProfile(), dnd: DndSettings = DndSettings()): String {
        val checked = uniqueName(name)
        validateAccountIdentity(profile)
        dnd.validate()
        val stored = localAccounts()
        require(stored.length() < 20) { "You can store up to 20 personal accounts." }
        val id = java.util.UUID.randomUUID().toString()
        val fields = SipAccountFields.encode(profile).apply {
            remove("password")
            if (profile.password.isNotEmpty()) put("passwordCipher", encrypt(profile.password))
        }
        fields.put("dnd", dnd.json())
        stored.put(JSONObject().put("id", id).put("name", checked).put("fields", fields))
        check(local.edit().putString("sipAccountsLocal", stored.toString()).putString("selectedAccountId", id).putString("lastPersonalAccountId", id).commit()) { "Could not save account." }
        return id
    }
    private fun uniqueName(name: String, excluding: String? = null): String = SipAccountFields.name(name).also { value ->
        require(accounts().none { it.id != excluding && it.name.equals(value, true) }) { "An account with that name already exists." }
    }
    fun renameAccount(name: String) {
        val selected = selectedAccount(); require(!selected.managed) { "This account is managed by your organization." }
        val checked = uniqueName(name, selected.id)
        val stored = localAccounts()
        (0 until stored.length()).map(stored::getJSONObject).first { it.getString("id") == selected.id }.put("name", checked)
        check(local.edit().putString("sipAccountsLocal", stored.toString()).commit()) { "Could not rename account." }
    }
    fun removeAccount() {
        val selected = selectedAccount(); require(!selected.managed) { "This account is managed by your organization." }
        val stored = localAccounts()
        val remaining = JSONArray((0 until stored.length()).map(stored::getJSONObject).filter { it.getString("id") != selected.id })
        val edit = local.edit().remove("selectedAccountId").remove("lastPersonalAccountId").remove("dndState.${selected.id}")
        if (remaining.length() == 0) edit.remove("sipAccountsLocal") else edit.putString("sipAccountsLocal", remaining.toString())
        // Do not resurrect the legacy credentials when the final account is removed.
        SipAccountFields.keys.forEach { edit.remove(it) }; edit.remove("passwordCipher")
        check(edit.commit()) { "Could not remove account." }
    }

    fun saveDnd(settings: DndSettings) {
        val selected = selectedAccount()
        settings.validate()
        if (selected.managed) {
            val overrides = accountOverrides()
            val fields = overrides.optJSONObject(selected.id) ?: JSONObject()
            val dnd = fields.optJSONObject("dnd") ?: JSONObject()
            val updated = settings.json()
            DndSettings.keys.filter { !isManaged(it) }.forEach { key ->
                val field = key.removePrefix("dnd").replaceFirstChar { it.lowercase() }
                dnd.put(field, updated.get(field))
            }
            fields.put("dnd", dnd); overrides.put(selected.id, fields)
            check(local.edit().putString("sipAccountOverrides", overrides.toString()).commit()) { "Could not save DND behaviour." }
        } else {
            val stored = localAccounts()
            (0 until stored.length()).map(stored::getJSONObject).first { it.getString("id") == selected.id }
                .getJSONObject("fields").put("dnd", settings.json())
            check(local.edit().putString("sipAccountsLocal", stored.toString()).commit()) { "Could not save DND behaviour." }
        }
    }

    var lastDialedNumber: String
        get() = data.getString("lastDialedNumber", "").orEmpty()
        set(value) { data.edit().putString("lastDialedNumber", value).apply() }

    var backgroundCalling: Boolean
        get() = data.getBoolean("backgroundCalling", true)
        set(value) { data.edit().putBoolean("backgroundCalling", value).apply() }

    var startOnBoot: Boolean
        get() = data.getBoolean("startOnBoot", true)
        set(value) { data.edit().putBoolean("startOnBoot", value).apply() }

    val keepAvailable: Boolean get() = backgroundCalling && accounts().any { it.profile.configured }

    var appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.name == data.getString("appearance", null) } ?: Appearance.SYSTEM
        set(value) { data.edit().putString("appearance", value.name).apply() }

    var transferMode: TransferMode
        get() = TransferMode.entries.firstOrNull { it.name == data.getString("transferMode", null) } ?: TransferMode.BOTH
        set(value) { data.edit().putString("transferMode", value.name).apply() }

    var dialpadTones: Boolean
        get() = data.getBoolean("dialpadTones", true)
        set(enabled) { data.edit().putBoolean("dialpadTones", enabled).apply() }

    var vibrateOnIncoming: Boolean
        get() = data.getBoolean("vibrateOnIncoming", false)
        set(enabled) { data.edit().putBoolean("vibrateOnIncoming", enabled).apply() }

    fun profile(): SipProfile = selectedAccount().profile

    private fun flatProfile(): SipProfile {
        fun s(k: String) = data.getString(k, "").orEmpty()
        return SipProfile(
            s("username"), s("authUser"), loadPassword(), s("displayName"), s("domain"),
            s("server"), s("proxy"), s("realm"),
            Transport.valueOf(s("transport").ifBlank { "UDP" }),
            data.getBoolean("register", true), data.getInt("expiry", 600),
            data.getInt("localPort", 0), data.getInt("rtpPort", 0),
            Encryption.valueOf(s("encryption").ifBlank { "None" }),
            Dtmf.valueOf(s("dtmf").ifBlank { "RTP" }), s("stun"), data.getBoolean("ice", false),
            data.getBoolean("echo", true), data.getBoolean("ipv6", true)
        )
    }

    fun save(profile: SipProfile) {
        val selected = selectedAccount()
        val candidate = SipAccountFields.encode(profile)
        val current = SipAccountFields.encode(selected.profile)
        SipAccountFields.keys.filter(::isManaged).forEach { candidate.put(it, current.get(it)) }
        validateAccountIdentity(SipAccountFields.decode(candidate, profile), selected.id, selected.managed)
        if (selected.managed) {
            val overrides = accountOverrides()
            val fields = overrides.optJSONObject(selected.id) ?: JSONObject()
            val updated = SipAccountFields.encode(profile)
            SipAccountFields.keys.filter { it != "password" && !isManaged(it) }.forEach { fields.put(it, updated.get(it)) }
            if (!isManaged("password")) {
                fields.remove("passwordCipher")
                if (profile.password.isNotEmpty()) fields.put("passwordCipher", encrypt(profile.password))
            }
            overrides.put(selected.id, fields)
            check(local.edit().putString("sipAccountOverrides", overrides.toString()).commit()) { "Could not save account settings." }
        } else {
            val stored = localAccounts()
            val fields = (0 until stored.length()).map(stored::getJSONObject)
                .first { it.getString("id") == selected.id }.getJSONObject("fields")
            val updated = SipAccountFields.encode(profile)
            SipAccountFields.keys.filter { it != "password" && !isManaged(it) }.forEach { fields.put(it, updated.get(it)) }
            if (!isManaged("password")) {
                fields.remove("passwordCipher")
                if (profile.password.isNotEmpty()) fields.put("passwordCipher", encrypt(profile.password))
            }
            check(local.edit().putString("sipAccountsLocal", stored.toString()).commit()) { "Could not save account." }
        }
        val encrypted = profile.password.takeIf { it.isNotEmpty() && !selected.managed }?.let(::encrypt)
        val editor = data.edit()
        if (!selected.managed) editor
            .putString("username", profile.username).putString("authUser", profile.authUser)
            .putString("passwordCipher", encrypted).putString("displayName", profile.displayName)
            .putString("domain", profile.domain).putString("server", profile.server)
            .putString("proxy", profile.proxy).putString("realm", profile.realm)
            .putString("transport", profile.transport.name).putBoolean("register", profile.register)
            .putInt("expiry", profile.expiry).putString("stun", profile.stun).putBoolean("ice", profile.ice)
        editor.putInt("localPort", profile.localPort)
            .putInt("rtpPort", profile.rtpPort).putString("encryption", profile.encryption.name)
            .putString("dtmf", profile.dtmf.name).putBoolean("echo", profile.echoCancellation)
            .putBoolean("ipv6", profile.ipv6).apply()
    }

    var ldapEnabled: Boolean
        get() = data.getBoolean("ldapEnabled", false)
        set(value) { data.edit().putBoolean("ldapEnabled", value).apply() }

    var ldapCertificateAlias: String
        get() = data.getString("ldapClientCertificateAlias", "").orEmpty()
        set(value) { data.edit().putString("ldapClientCertificateAlias", value).apply() }

    fun ldap(): LdapConfig = LdapConfig(
        host = data.getString("ldapHost", "").orEmpty(), port = data.getInt("ldapPort", 636),
        tls = data.getBoolean("ldapTls", true), bindDn = data.getString("ldapBindDn", "").orEmpty(),
        password = loadPassword("ldapPasswordCipher"), baseDn = data.getString("ldapBaseDn", "").orEmpty(),
        filter = data.getString("ldapFilter", "(objectClass=*)").orEmpty(),
        nameAttributes = data.getString("ldapNames", "displayName,cn,givenName,sn").orEmpty(),
        phoneAttributes = data.getString("ldapPhones", "telephoneNumber,mobile,homePhone,ipPhone").orEmpty(),
        clientCertificateAlias = ldapCertificateAlias,
    )

    fun saveLdap(config: LdapConfig) {
        config.validate()
        val encrypted = config.password.takeIf(String::isNotEmpty)?.let(::encrypt)
        data.edit().putString("ldapHost", config.host).putInt("ldapPort", config.port)
            .putBoolean("ldapTls", config.tls).putString("ldapBindDn", config.bindDn)
            .putString("ldapPasswordCipher", encrypted).putString("ldapBaseDn", config.baseDn)
            .putString("ldapFilter", config.filter).putString("ldapNames", config.nameAttributes)
            .putString("ldapPhones", config.phoneAttributes)
            .putString("ldapClientCertificateAlias", config.clientCertificateAlias).apply()
    }

    fun codecs(): List<Codec> {
        val byId = defaultCodecs.associateBy { it.id }
        val saved = JSONArray(data.getString("codecs", "[]"))
        val ordered = (0 until saved.length()).mapNotNull {
            val item = saved.getJSONObject(it)
            byId[item.getString("id")]?.copy(enabled = item.getBoolean("enabled"))
        }
        return ordered + defaultCodecs.filter { codec -> ordered.none { it.id == codec.id } }
    }

    fun saveCodecs(codecs: List<Codec>) {
        data.edit().putString("codecs", JSONArray(codecs.map {
            JSONObject().put("id", it.id).put("enabled", it.enabled)
        }).toString()).apply()
    }

    private fun secretKey(): SecretKey {
        val alias = "sippy-sip-credentials"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(alias, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun loadPassword(key: String = "passwordCipher"): String {
        val managedKey = if (key == "ldapPasswordCipher") "ldapPassword" else "password"
        if (useManaged && ManagedConfiguration.values.containsKey(managedKey)) return ManagedConfiguration.values[managedKey] as? String ?: ""
        val encrypted = data.getString(key, null) ?: return ""
        return decrypt(encrypted)
    }

    private fun decrypt(encrypted: String): String {
        decryptedSecrets[encrypted]?.let { return it }
        val plain = runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        }.getOrDefault("")
        if (decryptedSecrets.size > 100) decryptedSecrets.clear()
        if (plain.isNotEmpty()) decryptedSecrets[encrypted] = plain
        return plain
    }

    companion object {
        val defaultCodecs = listOf(
            Codec("opus", "Opus", true), Codec("g722", "G.722", true),
            Codec("pcma", "G.711 A-law (PCMA)", true), Codec("pcmu", "G.711 μ-law (PCMU)", true),
            Codec("g729", "G.729 / G.729A", false), Codec("amr", "AMR", false),
            Codec("amr-wb", "AMR-WB", false),
        )
    }
}
