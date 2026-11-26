package org.sippy

import android.app.Instrumentation
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.*
import java.security.KeyStore
import javax.net.ssl.*

/** Real TLS, binds and searches against an isolated fixture, never the user's directory. */
internal class LdapChecks(private val instrumentation: Instrumentation) {
    fun run() {
        val original = SSLContext.getDefault()
        val keys = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("ldap-fixture.p12").use { load(it, "fixture-only".toCharArray()) }
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        val client = SSLContext.getInstance("TLS").apply { init(emptyArray(), tm.trustManagers, null) }
        val identity = LdapIdentity(keys.getKey("ldap-fixture", "fixture-only".toCharArray()) as java.security.PrivateKey,
            keys.getCertificateChain("ldap-fixture").map { it as java.security.cert.X509Certificate }.toTypedArray())
        val mutual = LdapTls.context(identity, tm.trustManagers)
        val serverConfig = InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
            schema = null
            addAdditionalBindCredentials("cn=reader", "fixture-password")
            setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig("secure", null, 0, ssl.serverSocketFactory, ssl.socketFactory),
                InMemoryListenerConfig.createLDAPConfig("starttls", null, 0, ssl.socketFactory),
                InMemoryListenerConfig.createLDAPSConfig("mutual", null, 0, ssl.serverSocketFactory, client.socketFactory, true, true),
                InMemoryListenerConfig.createLDAPConfig("mutual-starttls", null, 0, ssl.socketFactory, true, true))
        }
        val server = InMemoryDirectoryServer(serverConfig)
        val prefs = Preferences(instrumentation.targetContext)
        val previous = prefs.data.all.filterKeys { it.startsWith("ldap") }
        try {
            server.startListening()
            SSLContext.setDefault(client)
            server.add(com.unboundid.ldap.sdk.Entry("dc=example,dc=com", Attribute("objectClass", "domain"), Attribute("dc", "example")))
            server.add(com.unboundid.ldap.sdk.Entry("uid=alice,dc=example,dc=com", Attribute("objectClass", "person"), Attribute("givenName", "Alice"),
                Attribute("sn", "Example"), Attribute("telephoneNumber", "+321234567"), Attribute("mobile", "01234")))
            server.add(com.unboundid.ldap.sdk.Entry("cn=Bob,dc=example,dc=com", Attribute("objectClass", "person"), Attribute("cn", "Bob"), Attribute("telephoneNumber", "5678")))
            val config = LdapConfig("localhost", server.getListenPort("secure"), true, "cn=reader", "fixture-password", "dc=example,dc=com")
            config.validate()
            prefs.saveLdap(config)
            check(prefs.ldap() == config) { "LDAP settings round trip failed" }
            check(!prefs.data.getString("ldapPasswordCipher", "").orEmpty().contains(config.password))
            prefs.saveLdap(config.copy(clientCertificateAlias = "fixture-alias"))
            check(prefs.ldap().clientCertificateAlias == "fixture-alias")
            prefs.ldapCertificateAlias = ""
            check(prefs.ldap().clientCertificateAlias.isEmpty())
            val missing = runCatching { LdapTls.socketFactory(config.copy(clientCertificateAlias = "sippy-nonexistent-test-key"), instrumentation.targetContext) }.exceptionOrNull()
            check(missing is IllegalArgumentException && missing.message.orEmpty().contains("Select it again")) { "Missing client key did not fail clearly" }
            (LdapTls.socketFactory(config).createSocket() as SSLSocket).use { socket ->
                check(socket.sslParameters.serverNames?.singleOrNull() == SNIHostName("localhost")) { "LDAP TLS lost the SNI hostname" }
            }
            val manager = LdapKeyManager(identity)
            val alias = manager.chooseClientAlias(arrayOf("RSA"), null, null)
            check(alias != null && manager.getPrivateKey(alias) === identity.key)
            check(manager.getCertificateChain(alias)?.size == identity.chain.size)
            check(manager.chooseClientAlias(arrayOf("EC"), null, null) == null)
            check(manager.chooseClientAlias(arrayOf("RSA"), arrayOf(javax.security.auth.x500.X500Principal("CN=Unknown CA")), null) == null)
            check(manager.getPrivateKey("unselected") == null && manager.getCertificateChain("unselected") == null)
            LdapDirectory().use { directory ->
                val all = directory.search(config, "")
                check(all.contacts.size == 3 && !all.limited) { "LDAP browse did not map all phone attributes" }
                check(all.contacts.any { it.name == "Alice Example" && it.number == "+321234567" }) { "LDAP full-name fallback failed" }
                check(directory.search(config, "Alice").contacts.size == 2)
                check(directory.search(config, "5678").contacts.single().name == "Bob")
                check(directory.search(config, "*)(objectClass=*)").contacts.isEmpty()) { "LDAP filter injection" }
            }
            LdapDirectory().use { directory ->
                check(directory.search(config.copy(port = server.getListenPort("starttls"), tls = false), "Bob").contacts.single().number == "5678") { "StartTLS search failed" }
            }
            for (startTls in listOf(false, true)) {
                val required = config.copy(port = server.getListenPort(if (startTls) "mutual-starttls" else "mutual"), tls = !startTls)
                check(runCatching { LdapDirectory().use { it.search(required, "Alice") } }.isFailure) { "Client-certificate server accepted no certificate" }
                LdapDirectory { settings ->
                    LdapTls.serverNameFactory(settings.host, mutual.socketFactory)
                }.use { directory ->
                    check(directory.search(required.copy(clientCertificateAlias = "fixture-alias"), "Alice").contacts.size == 2) { "Mutual TLS LDAP search failed" }
                }
                check(runCatching { LdapDirectory().use { it.search(required, "Alice") } }.isFailure) { "Cleared client certificate was still sent" }
            }
            for (i in 0..205) server.add(com.unboundid.ldap.sdk.Entry("cn=Limit$i,dc=example,dc=com",
                Attribute("objectClass", "person"), Attribute("cn", "Limit$i"), Attribute("telephoneNumber", "9$i")))
            LdapDirectory().use { directory ->
                val limited = directory.search(config, "Limit")
                check(limited.limited && limited.contacts.size == 200) { "LDAP size limit or partial results failed" }
            }
            // The SDK deliberately treats loopback IPs as hostname-safe. Use a real interface
            // address absent from the fixture certificate to verify mismatch rejection.
            val ownAddress = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }.firstOrNull {
                    it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                }
            if (ownAddress != null) check(runCatching {
                LdapDirectory().use { it.search(config.copy(host = ownAddress.hostAddress!!), "") }
            }.isFailure) { "TLS hostname mismatch accepted" }
            val bad = runCatching { LdapDirectory().use { it.search(config.copy(password = "wrong"), "") } }.exceptionOrNull()
            check(bad is LDAPException && bad.resultCode == ResultCode.INVALID_CREDENTIALS)
            check(!LdapDirectory.error(bad).contains("fixture-password"))
            SSLContext.setDefault(original)
            check(runCatching { LdapDirectory().use { it.search(config, "") } }.isFailure) { "Untrusted TLS certificate accepted" }
            check(runCatching { config.copy(port = 0).validate() }.isFailure)
            check(runCatching { config.copy(filter = "(").validate() }.isFailure)
        } finally {
            SSLContext.setDefault(original)
            prefs.data.edit().apply {
                prefs.data.all.keys.filter { it.startsWith("ldap") }.forEach { remove(it) }
                previous.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
            server.shutDown(true)
        }
    }
}
