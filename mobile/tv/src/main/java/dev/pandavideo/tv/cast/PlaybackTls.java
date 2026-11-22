package dev.pandavideo.tv.cast;

import dev.pandavideo.tv.R;

import android.content.Context;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/** Player-scoped TLS compatibility for Android 6 TVs with an outdated root store. */
final class PlaybackTls {
    private static final String ROOT_SHA256 =
        "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6";

    private PlaybackTls() { }

    static OkHttpClient createClient(Context context) {
        try (InputStream root = context.getResources().openRawResource(R.raw.isrg_root_x1)) {
            X509TrustManager trust = createTrustManager(root);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{trust}, null);
            // Keep OkHttp's hostname verifier and TLS policy. Never change process-wide
            // HTTPS defaults: only the Media3 manifest/segment/key/MP4 loader uses this.
            return new OkHttpClient.Builder()
                .sslSocketFactory(tls.getSocketFactory(), trust)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        } catch (Exception error) {
            // Resource/configuration failures must fail closed, without exposing a URL
            // or the underlying provider's potentially sensitive error message.
            throw new IllegalStateException("Playback TLS configuration unavailable");
        }
    }

    static X509TrustManager createTrustManager(InputStream root) throws Exception {
        X509Certificate certificate = (X509Certificate)
            CertificateFactory.getInstance("X.509").generateCertificate(root);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        if (!ROOT_SHA256.contentEquals(fingerprint) || certificate.getBasicConstraints() < 0)
            throw new CertificateException("Unexpected supplemental root");
        certificate.checkValidity();
        KeyStore supplement = KeyStore.getInstance(KeyStore.getDefaultType());
        supplement.load(null, null);
        supplement.setCertificateEntry("isrg-root-x1", certificate);
        return combine(trustManager(null), trustManager(supplement));
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers())
            if (manager instanceof X509TrustManager x509) return x509;
        throw new GeneralSecurityException("X509 trust manager unavailable");
    }

    static X509TrustManager combine(X509TrustManager system, X509TrustManager supplement) {
        return new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
                try { system.checkServerTrusted(chain, authType); }
                catch (CertificateException rejected) {
                    // This is a second full certificate-path validation, not an exception
                    // bypass. Unknown roots, expired leaves and bad signatures still fail.
                    supplement.checkServerTrusted(chain, authType);
                }
            }

            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] original = system.getAcceptedIssuers();
                X509Certificate[] extra = supplement.getAcceptedIssuers();
                X509Certificate[] all = Arrays.copyOf(original, original.length + extra.length);
                System.arraycopy(extra, 0, all, original.length, extra.length);
                return all;
            }
        };
    }
}
