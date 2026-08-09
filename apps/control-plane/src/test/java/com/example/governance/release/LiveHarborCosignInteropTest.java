package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in interoperability check; no live credentials are required for the default test suite. */
class LiveHarborCosignInteropTest {
    @Test
    void verifies_the_live_harbor_fixture_with_production_adapters() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("LIVE_HARBOR_COSIGN")));

        LiveFixture fixture = LiveFixture.load();
        List<VerificationEvidence> evidence = verify(fixture.settings(), fixture.request());

        assertThat(evidence).extracting(VerificationEvidence::type)
                .containsExactly("REGISTRY_DIGEST", "COSIGN_SIGNATURE", "SLSA_PROVENANCE");
        assertThat(evidence).allMatch(item -> fixture.request().artifact().digest().equals(item.digest()));
        assertThat(evidence).filteredOn(item -> "SLSA_PROVENANCE".equals(item.type()))
                .singleElement()
                .extracting(VerificationEvidence::subject)
                .isEqualTo("builder:" + fixture.settings().allowedBuilderIds().iterator().next()
                        + "|source:" + fixture.request().sourceRepository()
                        + "@" + fixture.request().sourceRevision());

        assertFailure(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED,
                withPassword(fixture.settings(), required("LIVE_WRONG_PASSWORD").toCharArray()), fixture.request());
        assertFailure(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED, fixture.settings(),
                fixture.requestFor(required("LIVE_UNAUTHORIZED_ARTIFACT")));
        assertFailure(ArtifactVerificationFailure.SIGNATURE_INVALID,
                withPublicKey(fixture.settings(), requiredPath("LIVE_WRONG_PUBLIC_KEY")), fixture.request());
        assertFailure(ArtifactVerificationFailure.PROVENANCE_INVALID,
                withPublicKey(fixture.settings(), requiredPath("LIVE_NO_ATTESTATION_PUBLIC_KEY")),
                fixture.requestFor(required("LIVE_ARTIFACT_NO_ATTESTATION")));
        assertFailure(ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH,
                withBuilders(fixture.settings(), Set.of(URI.create("https://builder.invalid/live"))), fixture.request());
        assertFailure(ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH, fixture.settings(),
                new ArtifactVerificationRequest(fixture.request().artifact(),
                        "https://git.example.internal/other/repository.git", fixture.request().sourceRevision()));
        assertFailure(ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH, fixture.settings(),
                new ArtifactVerificationRequest(fixture.request().artifact(), fixture.request().sourceRepository(),
                        "0000000000000000000000000000000000000000"));
        String unavailableRegistry = "harbor.localhost:9444";
        assertFailure(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE,
                withRegistry(fixture.settings(), unavailableRegistry), new ArtifactVerificationRequest(
                        ArtifactReference.parse(fixture.request().artifact().value().replace(fixture.settings().registry(), unavailableRegistry)),
                        fixture.request().sourceRepository(), fixture.request().sourceRevision()));
    }

    private static List<VerificationEvidence> verify(ArtifactVerificationSettings settings,
            ArtifactVerificationRequest request) {
        char[] password = settings.password();
        HarborRegistryClient harbor = new HarborRegistryClient(
                RegistryHttpClientFactory.create(settings), settings.registry(), settings.username(), password,
                settings.registryTimeout());
        return new HarborCosignArtifactVerifier(settings.registry(), harbor,
                new CosignClient(new ProcessCommandRunner(), settings),
                new SlsaProvenancePolicy(settings.allowedBuilderIds())).verify(request);
    }

    private static void assertFailure(ArtifactVerificationFailure expected, ArtifactVerificationSettings settings,
            ArtifactVerificationRequest request) {
        AtomicReference<List<VerificationEvidence>> evidence = new AtomicReference<>();
        assertThatThrownBy(() -> evidence.set(verify(settings, request)))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(expected);
        assertThat(evidence).hasValue(null);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "Missing " + name);
        return value;
    }

    private static Path requiredPath(String name) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    private record LiveFixture(ArtifactVerificationSettings settings, ArtifactVerificationRequest request) {
        private static LiveFixture load() {
            String registry = required("HARBOR_REGISTRY");
            ArtifactReference artifact = ArtifactReference.parse(required("LIVE_ARTIFACT"));
            ArtifactVerificationRequest request = new ArtifactVerificationRequest(
                    artifact, required("LIVE_SOURCE_REPOSITORY"), required("LIVE_SOURCE_REVISION"));
            return new LiveFixture(LiveHarborCosignInteropTest.settings(registry, required("HARBOR_USERNAME"), required("HARBOR_PASSWORD").toCharArray(),
                    requiredPath("COSIGN_PUBLIC_KEY"), Set.of(URI.create(required("SLSA_ALLOWED_BUILDER_IDS")))), request);
        }

        private ArtifactVerificationRequest requestFor(String artifact) {
            return new ArtifactVerificationRequest(ArtifactReference.parse(artifact), request.sourceRepository(),
                    request.sourceRevision());
        }
    }

    private static ArtifactVerificationSettings settings(String registry, String username, char[] password, Path publicKey,
            Set<URI> builders) {
        return new ArtifactVerificationSettings(registry, username, password, requiredPath("HARBOR_CA_CERT"),
                requiredPath("COSIGN_EXECUTABLE"), publicKey, builders, Duration.ofSeconds(10), Duration.ofSeconds(60),
                1024 * 1024);
    }

    private static ArtifactVerificationSettings withRegistry(ArtifactVerificationSettings settings, String registry) {
        return settings(registry, settings.username(), settings.password(), settings.cosignPublicKey(),
                settings.allowedBuilderIds());
    }

    private static ArtifactVerificationSettings withPassword(ArtifactVerificationSettings settings, char[] password) {
        return settings(settings.registry(), settings.username(), password, settings.cosignPublicKey(),
                settings.allowedBuilderIds());
    }

    private static ArtifactVerificationSettings withPublicKey(ArtifactVerificationSettings settings, Path publicKey) {
        return settings(settings.registry(), settings.username(), settings.password(), publicKey,
                settings.allowedBuilderIds());
    }

    private static ArtifactVerificationSettings withBuilders(ArtifactVerificationSettings settings, Set<URI> builders) {
        return settings(settings.registry(), settings.username(), settings.password(), settings.cosignPublicKey(), builders);
    }
}
