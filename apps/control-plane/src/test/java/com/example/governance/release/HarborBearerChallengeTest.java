package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HarborBearerChallengeTest {
    @Test
    void parses_case_insensitive_bearer_parameters_in_any_order() {
        HarborBearerChallenge challenge = HarborBearerChallenge.parse(
                "bEaReR scope=\"repository:team/app:pull\",service=\"harbor\\\"service\","
                        + "realm=\"https://registry.example.internal/service/token\"",
                "registry.example.internal", "team/app");

        assertThat(challenge.realm()).isEqualTo("https://registry.example.internal/service/token");
        assertThat(challenge.service()).isEqualTo("harbor\"service");
        assertThat(challenge.scope()).isEqualTo("repository:team/app:pull");
    }

    @Test
    void rejects_unsafe_or_malformed_challenges() {
        for (String challenge : new String[] {
                "Basic realm=\"https://registry.example.internal/token\"",
                "Bearer service=\"harbor\",scope=\"repository:team/app:pull\"",
                "Bearer realm=\"http://registry.example.internal/token\",scope=\"repository:team/app:pull\"",
                "Bearer realm=\"https://evil.example/token\",scope=\"repository:team/app:pull\"",
                "Bearer realm=\"https://registry.example.internal/token\",scope=\"repository:other/app:pull\"",
                "Bearer realm=\"https://registry.example.internal/token\",scope=\"repository:team/app:push\"",
                "Bearer realm=\"https://registry.example.internal/token\",realm=\"https://registry.example.internal/2\",scope=\"repository:team/app:pull\"",
                "Bearer realm=\"https://registry.example.internal/token\r\nX: bad\",scope=\"repository:team/app:pull\"",
                "Bearer realm=\"https://registry.example.internal/token,scope=\"repository:team/app:pull\""
        }) {
            assertThatThrownBy(() -> HarborBearerChallenge.parse(
                            challenge, "registry.example.internal", "team/app"))
                    .isInstanceOf(ArtifactVerificationException.class)
                    .extracting(error -> ((ArtifactVerificationException) error).failure())
                    .isEqualTo(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }
    }
}
