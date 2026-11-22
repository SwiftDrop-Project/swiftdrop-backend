package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ProofPhotoRef;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ProofPhotoRecord;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ProofPhotoRepository;
import com.swiftdrop.delivery.internal.storage.InMemoryProofImageStorage;
import com.swiftdrop.delivery.internal.storage.ProofImageStorage;
import com.swiftdrop.delivery.internal.storage.ProofPhotoProperties;
import com.swiftdrop.delivery.internal.storage.ProofStorageException;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Storing, attaching, reading and cleaning up proof photos, with the storage and the database failing on purpose. */
class ProofPhotosTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID PARCEL = UUID.randomUUID();
    private static final UUID COURIER = UUID.randomUUID();
    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private final ProofPhotoRepository photos = mock(ProofPhotoRepository.class);
    private final ParcelRepository parcels = mock(ParcelRepository.class);
    private final ProofImageStorage storage = spy(new InMemoryProofImageStorage());
    private final Parcel parcel = mock(Parcel.class);
    private final ProofPhotoProperties properties =
            new ProofPhotoProperties("memory", null, null, Duration.ofHours(24), true);

    /** Runs the callback in place (the real template opens a transaction). */
    private final TransactionTemplate inPlace = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    private final ProofPhotos proofPhotos = new ProofPhotos(photos, parcels, storage, properties, inPlace,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void anOutForDeliveryParcel() {
        when(parcels.findById(PARCEL)).thenReturn(Optional.of(parcel));
        when(parcel.getStatus()).thenReturn(ParcelStatus.OUT_FOR_DELIVERY);
        when(photos.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static ProofPhotoRecord record(UUID parcelId, UUID uploadedBy, Instant uploadedAt) {
        UUID id = UUID.randomUUID();
        return ProofPhotoRecord.uploaded(id, parcelId, uploadedBy, "MEMORY", id.toString(), "image/jpeg", 6,
                "0".repeat(64), uploadedAt);
    }

    @Test
    void aPhotoIsStoredThenRecordedWithADurableKeyAndNoPersonalData() {
        ProofPhotoRef ref = proofPhotos.store(PARCEL, COURIER, "image/jpeg", IMAGE);

        assertThat(ref.contentType()).isEqualTo("image/jpeg");
        assertThat(ref.sizeBytes()).isEqualTo(IMAGE.length);
        assertThat(ref.uploadedAt()).isEqualTo(NOW);
        verify(storage).put(eq(ref.photoId().toString()), eq(IMAGE), eq("image/jpeg"));
        verify(photos).saveAndFlush(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getParcelId().equals(PARCEL) && saved.getUploadedBy().equals(COURIER)
                        && saved.getStorageKey().equals(ref.photoId().toString()) && !saved.isAttached()
                        && saved.getSha256().length() == 64));
    }

    @Test
    void whenTheStorageIsDownNothingIsRecorded() {
        doThrow(new ProofStorageException("down")).when(storage).put(any(), any(), any());

        assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, "image/jpeg", IMAGE))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(photos, never()).saveAndFlush(any());
    }

    @Test
    void whenTheRowCantBeSavedTheStoredImageIsDeletedAgain() {
        when(photos.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("database down"));

        assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, "image/jpeg", IMAGE))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(storage).delete(any(), eq("image/jpeg"));
        assertThat(((InMemoryProofImageStorage) storage).size()).isZero();
    }

    @Test
    void aDeliveredParcelsProofIsFinal() {
        when(parcel.getStatus()).thenReturn(ParcelStatus.DELIVERED);

        assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, "image/jpeg", IMAGE))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TASK_STEP_NOT_ALLOWED));
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void wrongTypesAndSizesAreRefusedBeforeAnythingIsStored() {
        for (String type : List.of("image/gif", "application/pdf")) {
            assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, type, IMAGE))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, "image/jpeg", new byte[0]))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> proofPhotos.store(PARCEL, COURIER, "image/jpeg",
                new byte[(int) ProofPhotos.MAX_BYTES + 1]))
                .isInstanceOf(FieldValidationException.class);
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void attachingNeedsThisParcelsUnattachedPhotoFromTheSameCourier() {
        ProofPhotoRecord mine = record(PARCEL, COURIER, NOW);
        ProofPhotoRecord otherParcel = record(UUID.randomUUID(), COURIER, NOW);
        ProofPhotoRecord otherCourier = record(PARCEL, UUID.randomUUID(), NOW);
        ProofPhotoRecord attached = record(PARCEL, COURIER, NOW);
        attached.attach(NOW);
        for (ProofPhotoRecord photo : List.of(mine, otherParcel, otherCourier, attached)) {
            when(photos.findByIdForUpdate(photo.getId())).thenReturn(Optional.of(photo));
        }

        for (ProofPhotoRecord refused : List.of(otherParcel, otherCourier, attached)) {
            assertThatThrownBy(() -> proofPhotos.attach(PARCEL, refused.getId(), COURIER, "COURIER", NOW))
                    .isInstanceOfSatisfying(FieldValidationException.class,
                            e -> assertThat(e.fieldErrors()).containsOnlyKeys("proofPhotoId"));
        }
        assertThatThrownBy(() -> proofPhotos.attach(PARCEL, UUID.randomUUID(), COURIER, "COURIER", NOW))
                .isInstanceOf(FieldValidationException.class);

        proofPhotos.attach(PARCEL, mine.getId(), COURIER, "COURIER", NOW);
        assertThat(mine.getAttachedAt()).isEqualTo(NOW);
        verify(photos).save(mine);

        // An admin may attach the courier's upload (the only role that may also change status).
        proofPhotos.attach(PARCEL, otherCourier.getId(), UUID.randomUUID(), "ADMIN", NOW);
        assertThat(otherCourier.isAttached()).isTrue();
    }

    @Test
    void theAttachedPhotoIsReadFromStorage() {
        ProofPhotoRecord photo = record(PARCEL, COURIER, NOW);
        storage.put(photo.getStorageKey(), IMAGE, "image/jpeg");
        when(photos.findByParcelIdAndAttachedAtIsNotNull(PARCEL)).thenReturn(Optional.of(photo));

        assertThat(proofPhotos.findAttached(PARCEL)).get()
                .satisfies(found -> assertThat(found.content()).isEqualTo(IMAGE));
        assertThat(proofPhotos.findAttached(UUID.randomUUID())).isEmpty();

        doThrow(new ProofStorageException("down")).when(storage).get(any(), any());
        assertThatThrownBy(() -> proofPhotos.findAttached(PARCEL))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void theCleanUpRemovesOldUnattachedUploadsButNeverAnAttachedProof() {
        ProofPhotoRecord orphan = record(PARCEL, COURIER, NOW.minus(Duration.ofHours(30)));
        ProofPhotoRecord attachedMeanwhile = record(PARCEL, COURIER, NOW.minus(Duration.ofHours(30)));
        storage.put(orphan.getStorageKey(), IMAGE, "image/jpeg");
        storage.put(attachedMeanwhile.getStorageKey(), IMAGE, "image/jpeg");
        when(photos.findByAttachedAtIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(eq(NOW.minus(Duration.ofHours(24))),
                any())).thenReturn(List.of(orphan, attachedMeanwhile));
        when(photos.findByIdForUpdate(orphan.getId())).thenReturn(Optional.of(orphan));
        // Attached between the listing and the clean-up: the locked re-read sees it.
        ProofPhotoRecord nowAttached = record(PARCEL, COURIER, NOW.minus(Duration.ofHours(30)));
        nowAttached.attach(NOW);
        when(photos.findByIdForUpdate(attachedMeanwhile.getId())).thenReturn(Optional.of(nowAttached));

        assertThat(proofPhotos.removeOrphans()).isEqualTo(1);

        verify(photos).delete(orphan);
        verify(photos, never()).delete(nowAttached);
        InMemoryProofImageStorage memory = (InMemoryProofImageStorage) storage;
        assertThat(memory.contains(orphan.getStorageKey())).isFalse();
        assertThat(memory.contains(attachedMeanwhile.getStorageKey())).isTrue();
    }
}
