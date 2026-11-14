package com.swiftdrop.webhooks.internal.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The worker's writes to webhooks.deliveries: claim due rows, then record each result. Plain SQL (JdbcTemplate),
 * each statement in its own short transaction, so no connection is held while the merchant's server is called.
 *
 * <p>A claim is a lease: the row stays PENDING and its {@code next_attempt_at} moves to {@code leaseUntil}. Other
 * workers skip it until then; if this backend dies mid-send, the row simply becomes due again. Every result update
 * is guarded by {@code next_attempt_at = leaseUntil}: if the lease ran out and someone else took the row (or a
 * manual retry reset it), the stale result changes nothing. Times must be in microseconds, like PostgreSQL, for that
 * equality to match.
 */
@Repository
public class WebhookDeliveryAttempts {

    private static final String CLAIM = """
            UPDATE webhooks.deliveries d SET next_attempt_at = ?, updated_at = ?
            WHERE d.id IN (SELECT id FROM webhooks.deliveries
                           WHERE status = 'PENDING' AND next_attempt_at <= ?
                           ORDER BY next_attempt_at
                           LIMIT ?
                           FOR UPDATE SKIP LOCKED)
            RETURNING d.id""";

    private static final String SUCCEEDED = """
            UPDATE webhooks.deliveries
            SET status = 'SUCCEEDED', attempts = attempts + 1, last_status_code = ?, last_error = NULL,
                next_attempt_at = NULL, delivered_at = ?, updated_at = ?
            WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    private static final String RETRY_LATER = """
            UPDATE webhooks.deliveries
            SET attempts = attempts + 1, last_status_code = ?, last_error = ?, next_attempt_at = ?, updated_at = ?
            WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    private static final String FAILED = """
            UPDATE webhooks.deliveries
            SET status = 'FAILED', attempts = attempts + ?, last_status_code = ?, last_error = ?,
                next_attempt_at = NULL, updated_at = ?
            WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    private final JdbcTemplate jdbc;

    public WebhookDeliveryAttempts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Leases up to {@code limit} PENDING rows due at {@code now}, oldest first, and returns their ids. Rows another
     * worker is claiming at this moment are skipped, never waited for.
     */
    public List<UUID> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.queryForList(CLAIM, UUID.class, time(leaseUntil), time(now), time(now), limit);
    }

    /** A 2xx answer. False when the lease was lost (nothing changed). */
    public boolean succeeded(UUID id, Instant leaseUntil, int statusCode, Instant now) {
        return jdbc.update(SUCCEEDED, statusCode, time(now), time(now), id, time(leaseUntil)) == 1;
    }

    /** A failed attempt that will be tried again at {@code nextAttemptAt}. False when the lease was lost. */
    public boolean retryLater(UUID id, Instant leaseUntil, Integer statusCode, String error, Instant nextAttemptAt,
                              Instant now) {
        return jdbc.update(RETRY_LATER, code(statusCode), text(error), time(nextAttemptAt), time(now), id,
                time(leaseUntil)) == 1;
    }

    /**
     * No more automatic attempts. {@code countsAsAttempt} is false when nothing was sent (subscription turned off).
     * False when the lease was lost.
     */
    public boolean failed(UUID id, Instant leaseUntil, Integer statusCode, String error, boolean countsAsAttempt,
                          Instant now) {
        return jdbc.update(FAILED, countsAsAttempt ? 1 : 0, code(statusCode), text(error), time(now), id,
                time(leaseUntil)) == 1;
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Typed, so a null is bound as an INTEGER and PostgreSQL never has to guess. */
    private static SqlParameterValue code(Integer statusCode) {
        return new SqlParameterValue(Types.INTEGER, statusCode);
    }

    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }
}
