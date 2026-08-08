package com.example.governance.release;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !local")
public class RegistryArtifactVerifier implements ArtifactVerifier {
    private static final String OCI_ACCEPT = String.join(", ",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final HttpClient client;
    private final String allowedRegistry;

    @Autowired
    public RegistryArtifactVerifier(@Value("${governance.artifact-verification.allowed-registry:}") String allowedRegistry) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), allowedRegistry);
    }

    RegistryArtifactVerifier(HttpClient client, String allowedRegistry) {
        if (allowedRegistry == null || allowedRegistry.isBlank()) {
            throw new IllegalStateException(
                    "governance.artifact-verification.allowed-registry must be set (env: HARBOR_REGISTRY)");
        }
        this.client = client;
        this.allowedRegistry = allowedRegistry.trim();
    }

    @Override
    public List<VerificationEvidence> verify(ArtifactVerificationRequest request) {
        ArtifactReference artifact = request.artifact();
        if (!allowedRegistry.equals(artifact.registry())) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_NOT_ALLOWED);
        }

        URI manifestUri = URI.create("https://" + artifact.registry() + "/v2/" + artifact.repository()
                + "/manifests/" + artifact.digest());
        HttpRequest httpRequest = HttpRequest.newBuilder(manifestUri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", OCI_ACCEPT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<Void> response = client.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
            }
            String resolvedDigest = response.headers().firstValue("Docker-Content-Digest").orElse("");
            if (!artifact.digest().equals(resolvedDigest)) {
                throw new ArtifactVerificationException(ArtifactVerificationFailure.DIGEST_MISMATCH);
            }
            return List.of(new VerificationEvidence("REGISTRY_DIGEST", artifact.value(), artifact.digest()));
        } catch (IOException exception) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
        }
    }
}
