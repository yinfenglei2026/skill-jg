package com.example.governance.release;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test & !local")
public class ProductionArtifactVerificationConfiguration {
    @Bean
    ArtifactVerificationSettings artifactVerificationSettings(
            @Value("${governance.artifact-verification.allowed-registry:}") String registry,
            @Value("${governance.artifact-verification.username:}") String username,
            @Value("${governance.artifact-verification.password:}") String password,
            @Value("${governance.artifact-verification.registry-ca-cert:}") String caCert,
            @Value("${governance.artifact-verification.cosign-executable:}") String executable,
            @Value("${governance.artifact-verification.cosign-public-key:}") String publicKey,
            @Value("${governance.artifact-verification.allowed-builder-ids:}") String builders) {
        return new ArtifactVerificationSettings(
                registry, username, password.toCharArray(), optionalPath(caCert), requiredPath(executable),
                requiredPath(publicKey), builderIds(builders), null, null, 0);
    }

    @Bean
    RegistryHttpTransport registryHttpTransport(ArtifactVerificationSettings settings) {
        return RegistryHttpClientFactory.create(settings);
    }

    @Bean
    HarborRegistryClient harborRegistryClient(
            ArtifactVerificationSettings settings, RegistryHttpTransport transport) {
        return new HarborRegistryClient(transport, settings.registry(), settings.username(),
                settings.password(), settings.registryTimeout());
    }

    @Bean
    CommandRunner commandRunner() {
        return new ProcessCommandRunner();
    }

    @Bean
    CosignClient cosignClient(CommandRunner runner, ArtifactVerificationSettings settings) {
        return new CosignClient(runner, settings);
    }

    @Bean
    SlsaProvenancePolicy slsaProvenancePolicy(ArtifactVerificationSettings settings) {
        return new SlsaProvenancePolicy(settings.allowedBuilderIds());
    }

    @Bean
    ArtifactVerifier artifactVerifier(
            ArtifactVerificationSettings settings,
            HarborRegistryClient harbor,
            CosignClient cosign,
            SlsaProvenancePolicy policy) {
        return new HarborCosignArtifactVerifier(settings.registry(), harbor, cosign, policy);
    }

    private Path requiredPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath();
    }

    private Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath();
    }

    private Set<URI> builderIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(URI::create)
                .collect(Collectors.toUnmodifiableSet());
    }
}
