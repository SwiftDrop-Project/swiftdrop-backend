package com.swiftdrop.webhooks.internal.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves a host name to its addresses. Replaced by a fake in tests (no real DNS). */
public interface HostResolver {

    /**
     * All addresses of {@code host} (an ASCII host name or an IP literal). Must answer within about 3 seconds;
     * a timeout or any DNS error is an {@link UnknownHostException}.
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
