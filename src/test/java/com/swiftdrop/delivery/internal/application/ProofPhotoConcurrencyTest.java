package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.internal.domain.ProofPhotoRecord;
import com.swiftdrop.delivery.internal.persistence.ProofPhotoRepository;
import com.swiftdrop.delivery.internal.storage.InMemoryProofImageStorage;
import com.swiftdrop.delivery.internal.storage.ProofImageStorage;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The orphan clean-up against a delivery attaching the same photo at the same moment, on the real database: both lock
 * the photo row (SELECT ... FOR UPDATE), so the clean-up waits for the delivery and then sees the photo attached; an
 * attached proof is never removed. The other order: a photo already cleaned up can't be attached any more.
 */
class ProofPhotoConcurrencyTest extends IntegrationTest {

    @Autowired
    private ProofPhotos proofPhotos;

    @Autowired
    private ProofPhotoRepository photos;

    @Autowired
    private ProofImageStorage storage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** An unattached photo uploaded 30 hours ago (older than the 24-hour orphan age), with its image stored. */
    private ProofPhotoRecord oldUpload(UUID parcelId, UUID courierId) {
        UUID id = UUID.randomUUID();
        storage.put(id.toString(), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg");
        return photos.saveAndFlush(ProofPhotoRecord.uploaded(id, parcelId, courierId, storage.name(), id.toString(),
                "image/jpeg", 3, "0".repeat(64), Instant.now().minus(Duration.ofHours(30))));
    }

    @Test
    void theCleanUpWaitsForADeliveryAttachingThePhotoAndThenLeavesItAlone() throws Exception {
        assertThat(storage).isInstanceOf(InMemoryProofImageStorage.class);
        UUID parcelId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        ProofPhotoRecord photo = oldUpload(parcelId, courierId);
        CountDownLatch attachedNotCommitted = new CountDownLatch(1);
        CountDownLatch letDeliveryCommit = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // The delivery: locks and attaches the photo, then waits before committing.
        CompletableFuture<Void> delivery = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            proofPhotos.attach(parcelId, photo.getId(), courierId, "COURIER", Instant.now());
            attachedNotCommitted.countDown();
            await(letDeliveryCommit);
        }));
        assertThat(attachedNotCommitted.await(10, TimeUnit.SECONDS)).isTrue();

        // The clean-up starts meanwhile: it must block on the row lock, not remove the photo.
        CompletableFuture<Integer> cleanUp = CompletableFuture.supplyAsync(proofPhotos::removeOrphans);
        Thread.sleep(500);
        assertThat(cleanUp).as("the clean-up waits for the delivery's lock").isNotDone();

        letDeliveryCommit.countDown();
        delivery.get(10, TimeUnit.SECONDS);
        assertThat(cleanUp.get(10, TimeUnit.SECONDS)).isZero();

        ProofPhotoRecord after = photos.findById(photo.getId()).orElseThrow();
        assertThat(after.isAttached()).isTrue();
        assertThat(((InMemoryProofImageStorage) storage).contains(photo.getStorageKey())).isTrue();
    }

    @Test
    void aPhotoTheCleanUpRemovedCantBeAttachedAnyMore() {
        UUID parcelId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        ProofPhotoRecord photo = oldUpload(parcelId, courierId);

        assertThat(proofPhotos.removeOrphans()).isGreaterThanOrEqualTo(1);
        assertThat(photos.findById(photo.getId())).isEmpty();
        assertThat(((InMemoryProofImageStorage) storage).contains(photo.getStorageKey())).isFalse();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                proofPhotos.attach(parcelId, photo.getId(), courierId, "COURIER", Instant.now())))
                .isInstanceOf(FieldValidationException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
