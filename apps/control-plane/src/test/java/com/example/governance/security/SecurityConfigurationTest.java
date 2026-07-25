package com.example.governance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void maps_keycloak_read_only_role_to_the_method_security_authority() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("auditor@example.internal")
                .issuedAt(Instant.parse("2026-07-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-26T01:00:00Z"))
                .claim("realm_access", Map.of("roles", List.of("read-only")))
                .build();

        AbstractAuthenticationToken authentication = new SecurityConfiguration().jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_READ_ONLY");
    }
}
