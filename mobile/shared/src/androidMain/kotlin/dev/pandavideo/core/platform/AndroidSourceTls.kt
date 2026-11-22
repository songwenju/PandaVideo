package dev.pandavideo.core.platform

import android.content.Context
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Adds the official ISRG Root X1 only on Android 6, where the platform lacks this CA.
 * Source chains now use Root YR cross-signed by X1. Both Ktor/OkHttp and Media3's
 * HttpsURLConnection must receive the same trust roots; updating only catalog requests
 * leaves native video playback broken. Hostname verification remains the platform default.
 */
internal object AndroidSourceTls {
    private data class LegacyTrust(val manager: X509TrustManager, val sockets: SSLSocketFactory)
    private var legacy: LegacyTrust? = null
    private const val ROOT_SHA256 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"

    @Synchronized
    private fun trust(context: Context): LegacyTrust? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return null
        legacy?.let { return it }
        val certificate = context.assets.open("legacy-ca/isrgrootx1.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        // Pin the downloaded root's identity, never a leaf/server certificate. An asset
        // replacement must not silently broaden trust; normal PKIX validity still applies.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(fingerprint == ROOT_SHA256 && certificate.basicConstraints >= 0)
        certificate.checkValidity()
        val extraStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isrg-root-x1", certificate)
        }
        fun manager(store: KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
        val system = manager(null)
        val extra = manager(extraStore)
        val combined = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try { system.checkServerTrusted(chain, authType) }
                catch (_: CertificateException) { extra.checkServerTrusted(chain, authType) }
            }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + extra.acceptedIssuers
        }
        val sockets = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }.socketFactory
        return LegacyTrust(combined, sockets).also { legacy = it }
    }

    /** Configures native HTTPS for this app process; other applications are unaffected. */
    fun install(context: Context) {
        trust(context)?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it.sockets) }
    }

    /** Keeps explicit redirect policy while sharing verified legacy trust with native media. */
    fun httpClient(context: Context): HttpClient {
        val trusted = trust(context)
        trusted?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it.sockets) }
        return HttpClient(OkHttp) {
            followRedirects = false
            if (trusted != null) engine {
                config { sslSocketFactory(trusted.sockets, trusted.manager) }
            }
        }
    }
}
