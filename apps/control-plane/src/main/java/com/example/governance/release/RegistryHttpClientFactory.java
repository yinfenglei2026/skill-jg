package com.example.governance.release;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class RegistryHttpClientFactory {
    private static final long TOKEN_RESPONSE_LIMIT = 64 * 1024;

    private RegistryHttpClientFactory() {
    }

    public static JdkRegistryHttpTransport create(ArtifactVerificationSettings settings) {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(settings.registryTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER);
            if (settings.registryCaCert() != null) {
                builder.sslContext(trustContext(settings.registryCaCert()));
            }
            return new JdkRegistryHttpTransport(builder.build(), TOKEN_RESPONSE_LIMIT);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid registry CA certificate", exception);
        }
    }

    private static SSLContext trustContext(Path pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates;
        try (InputStream input = Files.newInputStream(pem)) {
            certificates = factory.generateCertificates(input);
        }
        if (certificates.size() != 1) {
            throw new IllegalArgumentException("registry CA must contain exactly one certificate");
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("registry-ca", certificates.iterator().next());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
