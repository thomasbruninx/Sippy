package org.sippy

import com.unboundid.ldap.sdk.*
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest
import com.unboundid.util.ssl.HostNameSSLSocketVerifier
import javax.net.ssl.SSLSocketFactory
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class LdapConfig(
    val host: String = "", val port: Int = 636, val tls: Boolean = true,
    val bindDn: String = "", val password: String = "", val baseDn: String = "",
    val filter: String = "(objectClass=*)", val nameAttributes: String = "displayName,cn,givenName,sn",
    val phoneAttributes: String = "telephoneNumber,mobile,homePhone,ipPhone",
    val clientCertificateAlias: String = "",
) {
    fun names() = attributes(nameAttributes)
    fun phones() = attributes(phoneAttributes)
    private fun attributes(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)
    fun validate() {
        require(host.isNotBlank() && !host.contains(Regex("[\\s/:@]"))) { "Enter an LDAP hostname without a protocol or port." }
        require(port in 1..65535) { "Enter an LDAP port between 1 and 65535." }
        require(DN.isValidDN(baseDn)) { "Enter a valid search base DN (or leave empty)." }
        require(bindDn.none(Char::isISOControl)) { "Enter a valid LDAP bind DN or username." }
        require(bindDn.isBlank() || password.isNotEmpty()) { "Enter the LDAP password, or leave the bind DN empty for anonymous access." }
        require(names().isNotEmpty() && phones().isNotEmpty() && (names() + phones()).all {
            it.matches(Regex("[a-zA-Z][a-zA-Z0-9-]*|[0-9]+(?:\\.[0-9]+)+"))
        }) { "Enter comma-separated LDAP attribute names." }
        require(runCatching { Filter.create(filter) }.isSuccess) { "Enter a valid LDAP search filter." }
    }
    fun searchFilter(query: String): Filter {
        val base = Filter.create(filter)
        val text = query.trim().take(128)
        if (text.isEmpty()) return base
        val fields = (names() + phones()).distinct().map {
            Filter.createSubstringFilter(it, null, arrayOf(text), null)
        }
        return Filter.createANDFilter(base, Filter.createORFilter(fields))
    }
}
internal data class LdapContact(val name: String, val number: String)
internal data class LdapResults(val contacts: List<LdapContact>, val limited: Boolean)

/** Read-only, bounded searches. Referrals are never followed with the user's credentials. */
internal class LdapDirectory(
    private val tlsFactory: (LdapConfig) -> SSLSocketFactory = { LdapTls.socketFactory(it) },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var connection: LDAPConnection? = null
    fun search(config: LdapConfig, query: String): LdapResults {
        config.validate()
        check(!closed.get())
        val options = LDAPConnectionOptions().apply {
            setConnectTimeoutMillis(8000)
            setResponseTimeoutMillis(12000)
            setFollowReferrals(false)
            setSSLSocketVerifier(HostNameSSLSocketVerifier(true, false))
        }
        val socketFactory = tlsFactory(config)
        val c = if (config.tls) LDAPConnection(socketFactory, options) else LDAPConnection(options)
        connection = c
        try {
            check(!closed.get())
            c.connect(config.host, config.port)
            // The alternative to LDAPS is LDAP with StartTLS, never a cleartext password bind.
            if (!config.tls) {
                val result = c.processExtendedOperation(StartTLSExtendedRequest(socketFactory))
                if (result.resultCode != ResultCode.SUCCESS) throw LDAPException(result.resultCode)
            }
            check(!closed.get())
            if (config.bindDn.isNotBlank()) c.bind(config.bindDn, config.password)
            val request = SearchRequest(config.baseDn, SearchScope.SUB, config.searchFilter(query),
                *(config.names() + config.phones()).distinct().toTypedArray()).apply {
                sizeLimit = 200
                timeLimitSeconds = 10
            }
            val result = try { c.search(request) } catch (e: LDAPSearchException) {
                if (e.resultCode != ResultCode.SIZE_LIMIT_EXCEEDED) throw e
                e.searchResult
            }
            return LdapResults(result.searchEntries.flatMap { entry -> contacts(config, entry) }
                .distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                result.resultCode == ResultCode.SIZE_LIMIT_EXCEEDED || result.entryCount >= 200)
        } finally { c.close(); connection = null }
    }
    override fun close() { closed.set(true); connection?.close() }
    companion object {
        fun contacts(config: LdapConfig, entry: SearchResultEntry): List<LdapContact> {
            val name = config.names().firstNotNullOfOrNull { attribute ->
                if (attribute.equals("givenName", true) || attribute.equals("sn", true)) {
                    listOfNotNull(entry.getAttributeValue("givenName"), entry.getAttributeValue("sn"))
                        .joinToString(" ").takeIf(String::isNotBlank)
                } else entry.getAttributeValue(attribute)?.takeIf(String::isNotBlank)
            }.orEmpty()
            return config.phones().flatMap { entry.getAttributeValues(it)?.toList().orEmpty() }
                .map(String::trim).filter { dialTarget(it) != null }.distinct()
                .map { LdapContact(name.ifBlank { it }, it) }
        }
        fun error(failure: Throwable): String {
            val detail = generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.joinToString(" ")
            return when {
                detail.contains("certificate_required", true) -> "The TLS server rejected a connection without an acceptable client certificate. Select a provider-approved certificate in Settings → LDAP, or ask your provider to check access to this endpoint. This happens before the LDAP password is checked."
                failure is LDAPException && failure.resultCode == ResultCode.INVALID_CREDENTIALS -> "LDAP authentication failed. Check the bind DN and password."
                failure is LDAPException && failure.resultCode == ResultCode.NO_SUCH_OBJECT -> "The LDAP search base was not found."
                failure is IllegalArgumentException -> failure.message ?: "Check the LDAP settings."
                else -> "Could not search LDAP. Check the server, TLS certificate, network and connection settings."
            }
        }
    }
}

/** Each view owns its request; leaving the view closes the connection and suppresses stale results. */
internal class LdapTask(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val directory = LdapDirectory { LdapTls.socketFactory(it, app) }
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    fun search(config: LdapConfig, query: String, done: (Result<LdapResults>) -> Unit) {
        executor.execute {
            val result = runCatching { directory.search(config, query) }
            handler.post { if (!closed.get()) done(result) }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        // Socket close can wait on the SDK reader; keep it off the UI thread.
        Thread { directory.close() }.start()
        handler.removeCallbacksAndMessages(null)
    }
}
