package dev.shareme

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

internal class TlsIdentity {
    val context: SSLContext
    val fingerprint: String
    init {
        val provider = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=ShareMe")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()), Date(now - 60_000), Date(now + 365L * 24 * 60 * 60 * 1000), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
        fingerprint = digest(certificate.encoded)
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex().toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("shareme", pair.private, password, arrayOf(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, SecureRandom()) }
    }
}

internal fun pinnedContext(fingerprint: String): SSLContext {
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("Client certificates are not used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            require(!chain.isNullOrEmpty()) { "Missing device certificate" }
            require(MessageDigest.isEqual(digest(chain[0].encoded).toByteArray(), fingerprint.toByteArray())) { "Device identity changed. Copy a new connection link from that device." }
            chain[0].checkValidity()
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
}
