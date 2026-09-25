package com.convo.audio_watermark.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Reads the authenticated caller's id back out of the SecurityContext that
 * JwtAuthenticationFilter populated from the request's JWT. WebConfig
 * requires authentication on every non-OPTIONS request, so by the time a
 * controller runs this is always present; the exception is a backstop.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("No authenticated user on this request");
        }
        return userId;
    }
}
