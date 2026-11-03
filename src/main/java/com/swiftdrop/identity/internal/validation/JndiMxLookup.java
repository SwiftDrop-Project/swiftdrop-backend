package com.swiftdrop.identity.internal.validation;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.time.Duration;
import java.util.Hashtable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * MX lookup with the JDK's built-in DNS client (JNDI), using the system's DNS servers. The whole lookup is capped
 * at {@link #TIMEOUT}; a timeout or any DNS error is {@code LOOKUP_FAILED}, never a rejection.
 */
@Component
public class JndiMxLookup implements MxLookup {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public MxStatus lookup(String domain) {
        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException ex) {
            return MxStatus.NOT_FOUND;
        }
        Future<MxStatus> result = executor.submit(() -> query(asciiDomain));
        try {
            return result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            result.cancel(true);
            return MxStatus.LOOKUP_FAILED;
        } catch (ExecutionException ex) {
            return MxStatus.LOOKUP_FAILED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return MxStatus.LOOKUP_FAILED;
        }
    }

    private static MxStatus query(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(domain + ".", new String[] {"MX"});
            Attribute mx = attributes.get("MX");
            if (mx == null || mx.size() == 0) {
                return MxStatus.NOT_FOUND;
            }
            NamingEnumeration<?> records = mx.getAll();
            while (records.hasMore()) {
                // "10 mail.example.com." - a null MX ("0 .") means the domain accepts no email (RFC 7505).
                String[] parts = String.valueOf(records.next()).trim().split("\\s+");
                String host = parts[parts.length - 1];
                if (!host.equals(".") && !host.isEmpty()) {
                    return MxStatus.FOUND;
                }
            }
            return MxStatus.NOT_FOUND;
        } catch (NameNotFoundException ex) {
            return MxStatus.NOT_FOUND;
        } catch (NamingException ex) {
            return MxStatus.LOOKUP_FAILED;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // nothing to clean up
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
