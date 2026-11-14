package com.swiftdrop;

import com.swiftdrop.webhooks.internal.security.HostResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Replaces the real DNS lookup for webhook URLs in tests (no network):
 * <ul>
 *   <li>IP literals are parsed, not looked up,</li>
 *   <li>{@code localhost} and hosts ending in {@code loopback.test} resolve to 127.0.0.1,</li>
 *   <li>hosts ending in {@code private.test} resolve to 10.0.0.5,</li>
 *   <li>hosts ending in {@code mixed.test} resolve to a public and a private address,</li>
 *   <li>hosts ending in {@code dns-down.test} make the lookup fail,</li>
 *   <li>everything else resolves to the public 93.184.216.34.</li>
 * </ul>
 */
public class FakeHostResolver implements HostResolver {

    public static final String PUBLIC_IP = "93.184.216.34";

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        if (host.contains(":") || host.matches("[0-9.]+")) {
            return List.of(InetAddress.getByName(host)); // a literal: no DNS
        }
        if (host.equals("localhost") || host.endsWith("loopback.test")) {
            return List.of(ip("127.0.0.1"));
        }
        if (host.endsWith("private.test")) {
            return List.of(ip("10.0.0.5"));
        }
        if (host.endsWith("mixed.test")) {
            return List.of(ip(PUBLIC_IP), ip("10.0.0.5"));
        }
        if (host.endsWith("dns-down.test")) {
            throw new UnknownHostException(host);
        }
        return List.of(ip(PUBLIC_IP));
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }
}
