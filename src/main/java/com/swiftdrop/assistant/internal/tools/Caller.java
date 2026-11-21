package com.swiftdrop.assistant.internal.tools;

import java.util.UUID;

/**
 * Who is asking. Tools never take the user from the model's arguments, only from here.
 *
 * @param userId    the logged-in user, or null
 * @param role      e.g. CUSTOMER, or null when anonymous
 * @param clientKey the client's IP address, for rate limits
 */
public record Caller(UUID userId, String role, String clientKey) {

    public static Caller anonymous(String clientKey) {
        return new Caller(null, null, clientKey);
    }

    public boolean loggedIn() {
        return userId != null;
    }

    /** Admins get the service reports and the admin data tools. */
    public boolean isAdmin() {
        return loggedIn() && "ADMIN".equals(role);
    }

    /** Customers and merchants have their own parcels. */
    public boolean ownsParcels() {
        return loggedIn() && ("CUSTOMER".equals(role) || "MERCHANT".equals(role));
    }
}
