package com.example.governance.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentActor {
    public Actor require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt) || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("An authenticated JWT is required");
        }

        String department = jwt.getClaimAsString("department");
        if (department == null || department.isBlank()) {
            throw new AccessDeniedException("JWT department claim is required");
        }
        return new Actor(jwt.getSubject(), department);
    }
}
