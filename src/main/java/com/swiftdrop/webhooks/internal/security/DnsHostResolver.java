package com.swiftdrop.webhooks.internal.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The JDK's resolver (system DNS), capped at {@link #TIMEOUT}. */
@Component
public class DnsHostResolver implements HostResolver {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        Future<InetAddress[]> result = executor.submit(() -> InetAddress.getAllByName(host));
        try {
            return List.of(result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            result.cancel(true);
            throw new UnknownHostException("DNS lookup timed out");
        } catch (ExecutionException ex) {
            throw new UnknownHostException("DNS lookup failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS lookup interrupted");
        }
    }
}
