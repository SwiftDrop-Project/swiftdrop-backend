package com.swiftdrop.shared.web;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Reads the logged-in user's id from the JWT {@code sub} claim. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
