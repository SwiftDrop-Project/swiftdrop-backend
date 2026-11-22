package com.swiftdrop.merchants.internal.security;

import com.swiftdrop.shared.web.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Extracts the merchant user id from either cookie auth (JwtAuthenticationToken) or API key auth (UsernamePasswordAuthenticationToken). */
public final class MerchantPrincipal {

    private MerchantPrincipal() {
    }

    public static UUID id(Authentication auth) {
        return switch (auth) {
            case JwtAuthenticationToken jwt -> CurrentUser.id(jwt.getToken());
            default -> (UUID) auth.getPrincipal();
        };
    }
}
