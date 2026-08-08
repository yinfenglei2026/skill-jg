package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in interoperability check; no live credentials are required for the default test suite. */
class LiveHarborCosignInteropTest {
    @Test
    void verifies_the_live_harbor_fixture_with_production_adapters() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("LIVE_HARBOR_COSIGN")));

        String registry = required("HARBOR_REGISTRY");
        String username = required("HARBOR_USERNAME");
        char[] password = required("HARBOR_PASSWORD").toCharArray();
        Path ca = requiredPath("HARBOR_CA_CERT");
        Path executable = requiredPath("COSIGN_EXECUTABLE");
        Path publicKey = requiredPath("COSIGN_PUBLIC_KEY");
        ArtifactReference artifact = ArtifactReference.parse(required("LIVE_ARTIFACT"));
        ArtifactVerificationRequest request = new ArtifactVerificationRequest(
                artifact, required("LIVE_SOURCE_REPOSITORY"), required("LIVE_SOURCE_REVISION"));
        Set<URI> builders = Set.of(URI.create(required("SLSA_ALLOWED_BUILDER_IDS")));

        ArtifactVerificationSettings settings = new ArtifactVerificationSettings(
                registry, username, password, ca, executable, publicKey, builders,
                Duration.ofSeconds(10), Duration.ofSeconds(60), 1024 * 1024);
        HarborRegistryClient harbor = new HarborRegistryClient(
                RegistryHttpClientFactory.create(settings), registry, username, password, settings.registryTimeout());
        List<VerificationEvidence> evidence = new HarborCosignArtifactVerifier(
                registry, harbor, new CosignClient(new ProcessCommandRunner(), settings),
                new SlsaProvenancePolicy(builders)).verify(request);

        assertThat(evidence).extracting(VerificationEvidence::type)
                .containsExactly("REGISTRY_DIGEST", "COSIGN_SIGNATURE", "SLSA_PROVENANCE");
        assertThat(evidence).allMatch(item -> request.artifact().digest().equals(item.digest()));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "Missing " + name);
        return value;
    }

    private static Path requiredPath(String name) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }
}
