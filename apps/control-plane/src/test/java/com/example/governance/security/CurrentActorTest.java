package com.example.governance.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentActorTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejects_an_authenticated_jwt_without_a_subject() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-08-02T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-02T01:00:00Z"))
                .claim("department", "customer-operations")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

        assertThatThrownBy(() -> new CurrentActor().require())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("JWT subject claim is required");
    }
}
