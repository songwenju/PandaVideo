package dev.pandavideo.tv.cast;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackTlsTest {
    private static final class Trust implements X509TrustManager {
        private final boolean accepts;
        private int calls;
        Trust(boolean accepts) { this.accepts = accepts; }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
            calls++;
            if (!accepts) throw new CertificateException("Rejected");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException { checkServerTrusted(chain, authType); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    @Test public void preservesSystemTrustWithoutFallback() throws Exception {
        Trust system = new Trust(true), extra = new Trust(false);
        PlaybackTls.combine(system, extra).checkServerTrusted(new X509Certificate[0], "RSA");
        assertEquals(1, system.calls);
        assertEquals(0, extra.calls);
    }

    @Test public void validatesWithSupplementWhenSystemRejects() throws Exception {
        Trust system = new Trust(false), extra = new Trust(true);
        PlaybackTls.combine(system, extra).checkServerTrusted(new X509Certificate[0], "RSA");
        assertEquals(1, system.calls);
        assertEquals(1, extra.calls);
    }

    @Test public void failsClosedWhenBothReject() {
        assertThrows(CertificateException.class, () -> PlaybackTls.combine(new Trust(false), new Trust(false))
            .checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test public void supplementalRootDoesNotExpandClientTrust() {
        assertThrows(CertificateException.class, () -> PlaybackTls.combine(new Trust(false), new Trust(true))
            .checkClientTrusted(new X509Certificate[0], "RSA"));
    }

    @Test public void bundledCertificateHasExpectedFingerprintAndIsIncluded() throws Exception {
        try (FileInputStream root = new FileInputStream("src/main/res/raw/isrg_root_x1.pem")) {
            X509TrustManager trust = PlaybackTls.createTrustManager(root);
            boolean found = false;
            for (X509Certificate issuer : trust.getAcceptedIssuers())
                if (issuer.getSubjectX500Principal().getName().contains("CN=ISRG Root X1")) found = true;
            assertTrue(found);
        }
    }

    @Test public void invalidResourceFailsClosed() {
        assertThrows(CertificateException.class, () -> PlaybackTls.createTrustManager(
            new ByteArrayInputStream("not a certificate".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
    }
}
