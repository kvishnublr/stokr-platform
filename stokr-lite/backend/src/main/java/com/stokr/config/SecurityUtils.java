package com.stokr.config;

import com.stokr.auth.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw new IllegalStateException("Not authenticated");
        }
        return user;
    }

    public static Long currentUserId() {
        return currentUser().getId();
    }
}
