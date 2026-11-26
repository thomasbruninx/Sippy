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

internal class Preferences(context: Context, private val useManaged: Boolean = true) {
    private val local = context.getSharedPreferences("sippy", Context.MODE_PRIVATE)
    val data = if (useManaged) ManagedPreferences(local) else local
    fun isManaged(key: String) = useManaged && ManagedConfiguration.isManaged(key)

    var backgroundCalling: Boolean
        get() = data.getBoolean("backgroundCalling", true)
        set(value) { data.edit().putBoolean("backgroundCalling", value).apply() }

    var startOnBoot: Boolean
        get() = data.getBoolean("startOnBoot", true)
        set(value) { data.edit().putBoolean("startOnBoot", value).apply() }

    val keepAvailable: Boolean get() = backgroundCalling &&
        !data.getString("username", "").isNullOrBlank() && !data.getString("domain", "").isNullOrBlank()

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

    fun profile(): SipProfile {
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
        val encrypted = profile.password.takeIf(String::isNotEmpty)?.let(::encrypt)
        data.edit()
            .putString("username", profile.username).putString("authUser", profile.authUser)
            .putString("passwordCipher", encrypted).putString("displayName", profile.displayName)
            .putString("domain", profile.domain).putString("server", profile.server)
            .putString("proxy", profile.proxy).putString("realm", profile.realm)
            .putString("transport", profile.transport.name).putBoolean("register", profile.register)
            .putInt("expiry", profile.expiry).putInt("localPort", profile.localPort)
            .putInt("rtpPort", profile.rtpPort).putString("encryption", profile.encryption.name)
            .putString("dtmf", profile.dtmf.name).putString("stun", profile.stun)
            .putBoolean("ice", profile.ice).putBoolean("echo", profile.echoCancellation)
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
        return runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        }.getOrDefault("")
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
