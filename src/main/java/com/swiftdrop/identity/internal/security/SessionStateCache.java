package com.swiftdrop.identity.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The current status and token version of each logged-in user, read by {@link SessionValidityFilter} on every
 * authenticated request. Cached for at most {@link #TTL}, so a change made on another backend instance takes effect
 * within 30 seconds; on this instance it takes effect at once, because every change calls {@link #evict}.
 */
@Component
public class SessionStateCache {

    static final Duration TTL = Duration.ofSeconds(30);

    /** What a session check needs to know about a user. */
    public record SessionState(UserStatus status, int tokenVersion) {
    }

    private final UserRepository users;
    private final Cache<UUID, Optional<SessionState>> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(100_000)
            .build();

    public SessionStateCache(UserRepository users) {
        this.users = users;
    }

    /** Empty when the user no longer exists. */
    public Optional<SessionState> get(UUID userId) {
        return cache.get(userId, id -> users.findById(id)
                .map(user -> new SessionState(user.getStatus(), user.getTokenVersion())));
    }

    /**
     * Forgets the cached state now and again after the current transaction commits, so a request running in
     * between cannot put the old state back for 30 seconds.
     */
    public void evict(UUID userId) {
        cache.invalidate(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(userId);
                }
            });
        }
    }

    /** For tests. */
    public void clear() {
        cache.invalidateAll();
    }
}
