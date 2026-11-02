package com.swiftdrop.identity.internal.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from ADMIN_EMAIL, ADMIN_PASSWORD, SEED_DEMO_USERS and DEMO_PASSWORD. */
@ConfigurationProperties("swiftdrop.seed")
public record SeedProperties(String adminEmail, String adminPassword, boolean demoUsers, String demoPassword) {

    /** Keeps the passwords out of logs. */
    @Override
    public String toString() {
        return "SeedProperties[demoUsers=" + demoUsers + "]";
    }
}
