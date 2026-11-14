package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim and lease against the real database. Rows here are due in 2001, so deliveries other test classes leave
 * PENDING (due "now") are never claimed by these tests; every row is deleted afterwards.
 */
class WebhookDeliveryAttemptsTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2001-01-01T10:00:00Z");
    private static final Instant LEASE = NOW.plusSeconds(60);

    @Autowired
    WebhookDeliveryAttempts attempts;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final UUID merchant = UUID.randomUUID();

    @AfterEach
    void deleteRows() {
        jdbc.update("delete from webhooks.deliveries where merchant_id = ?", merchant);
    }

    @Test
    void aClaimLeasesDueRowsOldestFirstAndKeepsThemPending() {
        UUID older = pending(NOW.minusSeconds(120));
        UUID newer = pending(NOW.minusSeconds(10));
        UUID notYetDue = pending(NOW.plusSeconds(30));

        List<UUID> claimed = attempts.claimDue(NOW, LEASE, 10);

        assertThat(claimed).containsExactlyInAnyOrder(older, newer).doesNotContain(notYetDue);
        assertThat(row(older)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(nextAttempt(older)).isEqualTo(LEASE);
        assertThat(attempts.claimDue(NOW, LEASE, 10)).as("already leased").isEmpty();
    }

    @Test
    void theLimitTakesTheOldestFirst() {
        UUID older = pending(NOW.minusSeconds(120));
        pending(NOW.minusSeconds(10));

        assertThat(attempts.claimDue(NOW, LEASE, 1)).containsExactly(older);
    }

    @Test
    void aLeasedRowIsNotClaimedAgainUntilTheLeaseEnds() {
        UUID id = pending(NOW);
        assertThat(attempts.claimDue(NOW, LEASE, 10)).containsExactly(id);

        assertThat(attempts.claimDue(NOW.plusSeconds(30), NOW.plusSeconds(90), 10)).isEmpty();
        // the backend that claimed it died: after the lease it is due again
        assertThat(attempts.claimDue(LEASE, LEASE.plusSeconds(60), 10)).containsExactly(id);
    }

    @Test
    void aRowAnotherWorkerIsClaimingIsSkippedNotWaitedFor() throws Exception {
        UUID id = pending(NOW);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> otherWorker = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForList("select id from webhooks.deliveries where id = ? for update", UUID.class, id);
                    locked.countDown();
                    await(release);
                }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(attempts.claimDue(NOW, LEASE, 10)).doesNotContain(id);
        } finally {
            release.countDown();
            otherWorker.get(10, TimeUnit.SECONDS);
        }
        assertThat(attempts.claimDue(NOW, LEASE, 10)).containsExactly(id);
    }

    @Test
    void successFinishesTheRow() {
        UUID id = claimedRow();

        assertThat(attempts.succeeded(id, LEASE, 204, NOW.plusSeconds(1))).isTrue();

        assertThat(row(id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1)
                .containsEntry("last_status_code", 204).containsEntry("last_error", null)
                .containsEntry("next_attempt_at", null);
        assertThat(row(id).get("delivered_at")).isNotNull();
    }

    @Test
    void aRetryCountsTheAttemptAndMovesTheNextOne() {
        UUID id = claimedRow();
        Instant next = NOW.plus(Duration.ofMinutes(1));

        assertThat(attempts.retryLater(id, LEASE, null, "TIMEOUT", next, NOW)).isTrue();

        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_status_code", null).containsEntry("last_error", "TIMEOUT");
        assertThat(nextAttempt(id)).isEqualTo(next);
    }

    @Test
    void aFailureCanBeCountedOrNot() {
        UUID sent = claimedRow();
        UUID notSent = claimedRow();

        assertThat(attempts.failed(sent, LEASE, 404, "HTTP_404", true, NOW)).isTrue();
        assertThat(attempts.failed(notSent, LEASE, null, "SUBSCRIPTION_INACTIVE", false, NOW)).isTrue();

        assertThat(row(sent)).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_status_code", 404).containsEntry("next_attempt_at", null);
        assertThat(row(notSent)).containsEntry("status", "FAILED").containsEntry("attempts", 0)
                .containsEntry("last_error", "SUBSCRIPTION_INACTIVE");
    }

    @Test
    void aResultAfterTheLeaseWasLostChangesNothing() {
        UUID id = claimedRow();
        // the lease ran out and another worker claimed it with its own lease
        assertThat(attempts.claimDue(LEASE, LEASE.plusSeconds(60), 10)).containsExactly(id);

        assertThat(attempts.succeeded(id, LEASE, 200, NOW)).isFalse();
        assertThat(attempts.retryLater(id, LEASE, 500, "HTTP_500", NOW.plusSeconds(60), NOW)).isFalse();
        assertThat(attempts.failed(id, LEASE, 400, "HTTP_400", true, NOW)).isFalse();

        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0)
                .containsEntry("last_error", null);
    }

    @Test
    void aFinishedRowIsNeverClaimedOrChanged() {
        UUID id = claimedRow();
        attempts.succeeded(id, LEASE, 200, NOW);

        assertThat(attempts.claimDue(LEASE.plusSeconds(3600), LEASE.plusSeconds(3660), 10)).doesNotContain(id);
        assertThat(attempts.failed(id, LEASE, 500, "HTTP_500", true, NOW)).isFalse();
    }

    // ---------- helpers ----------

    private UUID pending(Instant due) {
        return deliveries.saveAndFlush(WebhookDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), merchant,
                WebhookEventType.PARCEL_DELIVERED, "{\"id\":\"x\"}", due)).getId();
    }

    private UUID claimedRow() {
        UUID id = pending(NOW);
        List<UUID> claimed = attempts.claimDue(NOW, LEASE, 10);
        assertThat(claimed).contains(id);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select * from webhooks.deliveries where id = ?", id);
    }

    private Instant nextAttempt(UUID id) {
        OffsetDateTime value = jdbc.queryForObject("select next_attempt_at from webhooks.deliveries where id = ?",
                OffsetDateTime.class, id);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
