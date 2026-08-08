package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionArtifactVerificationConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void creates_the_production_verifier_without_performing_io() throws Exception {
        Path key = Files.writeString(tempDir.resolve("cosign.pub"),
                "-----BEGIN PUBLIC KEY-----\nAQID\n-----END PUBLIC KEY-----\n");
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(ProductionArtifactVerificationConfiguration.class)
                .withPropertyValues(
                        "governance.artifact-verification.allowed-registry=registry.example.internal",
                        "governance.artifact-verification.username=robot",
                        "governance.artifact-verification.password=secret",
                        "governance.artifact-verification.cosign-executable=" + javaExecutable(),
                        "governance.artifact-verification.cosign-public-key=" + key,
                        "governance.artifact-verification.allowed-builder-ids=https://builder.example/internal")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ArtifactVerifier.class);
                    assertThat(context.getBean(ArtifactVerifier.class))
                            .isInstanceOf(HarborCosignArtifactVerifier.class);
                });
    }

    @Test
    void is_not_active_in_test_or_local_profiles() {
        for (String profile : new String[] {"test", "local"}) {
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .withUserConfiguration(ProductionArtifactVerificationConfiguration.class)
                    .run(context -> assertThat(context).doesNotHaveBean(ArtifactVerifier.class));
        }
    }

    @Test
    void rejects_relative_cosign_paths() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(ProductionArtifactVerificationConfiguration.class)
                .withPropertyValues(
                        "governance.artifact-verification.allowed-registry=registry.example.internal",
                        "governance.artifact-verification.username=robot",
                        "governance.artifact-verification.password=secret",
                        "governance.artifact-verification.cosign-executable=cosign",
                        "governance.artifact-verification.cosign-public-key=cosign.pub",
                        "governance.artifact-verification.allowed-builder-ids=https://builder.example/internal")
                .run(context -> assertThat(context).hasFailed());
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().toString();
    }
}
