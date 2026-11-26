package org.sippy

import android.content.Context
import android.security.KeyChain
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal data class LdapIdentity(val key: PrivateKey, val chain: Array<X509Certificate>)

internal object LdapTls {
    fun socketFactory(config: LdapConfig, context: Context? = null): SSLSocketFactory {
        val delegate = if (config.clientCertificateAlias.isEmpty()) SSLContext.getDefault().socketFactory else {
            val app = requireNotNull(context) { "Select the LDAP client certificate again in Settings → LDAP." }.applicationContext
            val identity = try {
                val key = KeyChain.getPrivateKey(app, config.clientCertificateAlias)
                val chain = KeyChain.getCertificateChain(app, config.clientCertificateAlias)
                require(key != null && !chain.isNullOrEmpty()) { "unavailable" }
                chain.first().checkValidity()
                LdapIdentity(key, chain)
            } catch (_: Exception) {
                throw IllegalArgumentException("The selected LDAP client certificate is unavailable, expired or not permitted. Select it again in Settings → LDAP, or clear the selection.")
            }
            context(identity).socketFactory
        }
        return serverNameFactory(config.host, delegate)
    }

    // A separate TLS context preserves normal server trust validation. Private keys stay in Android's KeyChain.
    fun context(identity: LdapIdentity, trust: Array<TrustManager>? = null): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(LdapKeyManager(identity)), trust, null) }

    fun serverNameFactory(host: String, delegate: SSLSocketFactory): SSLSocketFactory =
        LdapSocketFactory(host, delegate)
}

internal class LdapKeyManager(private val identity: LdapIdentity) : X509ExtendedKeyManager() {
    private val alias = "ldap-client"
    private fun accepts(type: String, issuers: Array<out Principal>?): Boolean {
        val keyType = type.substringBefore('_')
        return keyType.equals(identity.key.algorithm, true) &&
            (issuers.isNullOrEmpty() || identity.chain.any { cert ->
                issuers.any { it == cert.issuerX500Principal || it == cert.subjectX500Principal }
            })
    }
    override fun getClientAliases(keyType: String, issuers: Array<out Principal>?) =
        if (accepts(keyType, issuers)) arrayOf(alias) else null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        if (keyType?.any { accepts(it, issuers) } == true) alias else null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        chooseClientAlias(keyType, issuers, null)
    override fun getCertificateChain(alias: String?) = if (alias == this.alias) identity.chain.copyOf() else null
    override fun getPrivateKey(alias: String?) = if (alias == this.alias) identity.key else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = null
}

/** The LDAP SDK connects by IP; explicitly retain the configured DNS name in the TLS handshake. */
private class LdapSocketFactory(private val host: String, private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    private fun configured(socket: Socket): Socket = (socket as SSLSocket).apply {
        val parameters = sslParameters
        val numeric = host.contains(':') || host.matches(Regex("[0-9.]+"))
        if (!numeric) parameters.serverNames = listOf(SNIHostName(host))
        sslParameters = parameters
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = configured(delegate.createSocket())
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configured(delegate.createSocket(socket, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = configured(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        configured(delegate.createSocket(host, port, local, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = configured(delegate.createSocket(host, port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        configured(delegate.createSocket(host, port, local, localPort))
}
