package com.swiftdrop.merchants.internal.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The part of the seed settings the demo merchant needs: ADMIN_EMAIL, SEED_DEMO_USERS and DEMO_PASSWORD. */
@ConfigurationProperties("swiftdrop.seed")
public record DemoSeedProperties(String adminEmail, boolean demoUsers, String demoPassword) {

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "DemoSeedProperties[demoUsers=" + demoUsers + "]";
    }
}
