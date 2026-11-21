package com.swiftdrop.courier;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ProofPhoto;
import com.swiftdrop.delivery.ProofPhotoRef;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proof of delivery with the REAL delivery and dispatch modules and the in-memory image storage (no Cloudinary in
 * tests): the courier uploads a photo of the signed delivery slip, then marks the parcel DELIVERED with it. The photo,
 * the status, the history row and the outbox event are kept together; a parcel can't be DELIVERED without a photo,
 * with another parcel's photo or with another courier's upload, and its proof can't be replaced afterwards.
 */
class CourierProofOfDeliveryTest extends RealWorkflowTest {

    /** A parcel from Delgoda to Gampaha town: picked up and delivered from the Gampaha branch. */
    private UUID gampahaParcel() throws Exception {
        return bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
    }

    /** A parcel booked in Gampaha, collected and handed over there, and now out for delivery with {@code courier}. */
    private UUID outForDelivery(StaffCourier courier) throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        UUID parcel = gampahaParcel();
        pickupTask(pickupCourier, "GAMPAHA", parcel);
        moveTo(pickupCourier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk());
        deliveryTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        return parcel;
    }

    private ResultActions upload(StaffCourier courier, UUID parcelId, byte[] content) throws Exception {
        return mvc.perform(multipart(COURIER + "/" + parcelId + "/proof")
                .file(new MockMultipartFile("file", "slip.jpg", "image/jpeg", content))
                .cookie(courier.cookie()));
    }

    private ResultActions deliverWith(StaffCourier courier, UUID parcelId, Object photoId) throws Exception {
        return changeStatus(courier, parcelId, Map.of("status", "DELIVERED", "recipientName", "Kamala Silva",
                "proofPhotoId", photoId.toString()));
    }

    private String proofRow(String column, UUID photoId) {
        return jdbc.queryForObject("select " + column + "::text from delivery.proof_photos where id = ?",
                String.class, photoId);
    }

    @Test
    void theCourierUploadsTheSignedSlipThenDeliversAndTheProofIsLinkedToTheParcel() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);

        JsonNode uploaded = read(upload(courier, parcel, CourierApiTest.jpeg())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.sizeBytes").isNumber())
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty()));
        UUID photo = UUID.fromString(uploaded.get("proofPhotoId").asString());
        assertThat(uploaded.has("url")).as("no URL: the image is private").isFalse();
        assertThat(proofRow("attached_at", photo)).as("not attached before DELIVERED").isNull();
        assertThat(proofRow("parcel_id", photo)).isEqualTo(parcel.toString());
        assertThat(proofRow("storage", photo)).isEqualTo("MEMORY");
        assertThat(proofRow("storage_key", photo)).as("key made from the photo id only").contains(photo.toString());

        deliverWith(courier, parcel, photo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.proof.recipientName").value("Kamala Silva"))
                .andExpect(jsonPath("$.proof.hasPhoto").value(true));

        // The parcel points to the photo, and the photo is attached, in the same change.
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(parcel(parcel).hasProofPhoto()).isTrue();
        assertThat(jdbc.queryForObject("select proof_photo_id::text from delivery.parcels where id = ?", String.class,
                parcel)).isEqualTo(photo.toString());
        assertThat(proofRow("attached_at", photo)).isNotNull();

        // History lists it with the proof flag; the courier who delivered it can see the photo, never cached.
        history(courier).andExpect(jsonPath("$.content[0].proof.hasPhoto").value(true));
        byte[] image = mvc.perform(get(COURIER + "/" + parcel + "/proof").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(image).startsWith((byte) 0xFF, (byte) 0xD8);

        // The contract for other modules (e.g. WP7's "View delivery proof"): the photo by parcel id.
        ProofPhoto viaApi = deliveries.findProofPhoto(parcel).orElseThrow();
        assertThat(viaApi.contentType()).isEqualTo("image/jpeg");
        assertThat(viaApi.content()).isEqualTo(image);
    }

    @Test
    void deliveredWithoutAPhotoIsRefusedAndTheParcelStaysOutForDelivery() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);

        changeStatus(courier, parcel, Map.of("status", "DELIVERED", "recipientName", "Kamala Silva"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.proofPhotoId").value("Add a photo of the signed delivery slip."));

        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(deliveries.findProofPhoto(parcel)).isEmpty();
    }

    @Test
    void anUnknownPhotoIdIsRefusedAndNothingIsKept() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);
        int history = historyRows(parcel);
        int outbox = outboxRows(parcel);

        deliverWith(courier, parcel, UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.proofPhotoId").value("Add the photo again."));

        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(historyRows(parcel)).isEqualTo(history);
        assertThat(outboxRows(parcel)).isEqualTo(outbox);
        assertThat(taskStatusRow(UUID.fromString(read(detail(courier, parcel)).get("task").get("assignmentId")
                .asString()))).isEqualTo("IN_PROGRESS");
    }

    @Test
    void anotherParcelsPhotoCantBeAttached() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID first = outForDelivery(courier);
        UUID second = outForDelivery(courier);
        UUID secondsPhoto = uploadProof(courier, second);

        deliverWith(courier, first, secondsPhoto)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.proofPhotoId").value("Add the photo again."));

        assertThat(parcel(first).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(proofRow("attached_at", secondsPhoto)).isNull();
        // It still works for its own parcel.
        deliverWith(courier, second, secondsPhoto).andExpect(status().isOk());
    }

    @Test
    void aPhotoSomeoneElseUploadedCantBeAttachedByTheCourier() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);
        ProofPhotoRef someoneElses = deliveries.storeProofPhoto(parcel, UUID.randomUUID(), "image/jpeg",
                CourierApiTest.jpeg());

        deliverWith(courier, parcel, someoneElses.photoId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.proofPhotoId").value("Add the photo again."));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void onlyTheAssignedCourierMayUploadOrSeeTheProof() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        StaffCourier other = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);

        upload(other, parcel, CourierApiTest.jpeg()).andExpect(status().isNotFound());

        deliver(courier, parcel, "Kamala Silva").andExpect(status().isOk());
        mvc.perform(get(COURIER + "/" + parcel + "/proof").cookie(other.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(COURIER + "/" + parcel + "/proof").cookie(customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePickupCourierCantUploadOrSeeTheDeliveryProof() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("GAMPAHA");
        UUID parcel = gampahaParcel();
        pickupTask(pickupCourier, "GAMPAHA", parcel);

        // Proof is for deliveries only: a pickup step refuses it.
        upload(pickupCourier, parcel, CourierApiTest.jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));

        moveTo(pickupCourier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk());
        deliveryTask(deliveryCourier, "GAMPAHA", parcel);

        // Not out for delivery yet: no proof either.
        upload(deliveryCourier, parcel, CourierApiTest.jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));

        moveTo(deliveryCourier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(deliveryCourier, parcel, "Kamala Silva").andExpect(status().isOk());
        mvc.perform(get(COURIER + "/" + parcel + "/proof").cookie(pickupCourier.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeliveredParcelsProofCantBeReplacedOrDeliveredTwice() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);
        UUID first = uploadProof(courier, parcel);
        UUID unused = uploadProof(courier, parcel); // changed their mind before confirming: fine
        deliverWith(courier, parcel, first).andExpect(status().isOk());

        // It was the task's only parcel, so the task completed by itself: the task is closed (checked before the step).
        upload(courier, parcel, CourierApiTest.jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        deliverWith(courier, parcel, unused)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));

        assertThat(jdbc.queryForObject("select proof_photo_id::text from delivery.parcels where id = ?", String.class,
                parcel)).isEqualTo(first.toString());
        assertThat(proofRow("attached_at", unused)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from delivery.proof_photos where parcel_id = ?"
                + " and attached_at is not null", Integer.class, parcel)).isEqualTo(1);
    }

    @Test
    void whileASiblingIsStillOpenADeliveredParcelsProofIsStillFinal() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier courier = createCourier("GAMPAHA");
        UUID delivered = gampahaParcel();
        UUID sibling = gampahaParcel();
        pickupTask(pickupCourier, "GAMPAHA", delivered, sibling);
        for (UUID parcel : new UUID[]{delivered, sibling}) {
            moveTo(pickupCourier, parcel, "PICKED_UP").andExpect(status().isOk());
            handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk());
        }
        deliveryTask(courier, "GAMPAHA", delivered, sibling);
        moveTo(courier, delivered, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        moveTo(courier, sibling, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        UUID unused = uploadProof(courier, delivered);
        deliver(courier, delivered, "Kamala Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));

        // The task is still open (the sibling is out for delivery), so the step itself refuses.
        upload(courier, delivered, CourierApiTest.jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        deliverWith(courier, delivered, unused)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        // The sibling is completed on its own, with its own proof; the task then completes by itself.
        deliver(courier, sibling, "Nimal Perera")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
    }

    @Test
    void theImageIsCheckedAndItsMetadataRemoved() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = outForDelivery(courier);

        upload(courier, parcel, "not an image".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value("Use a JPEG, PNG or WebP photo."));
        byte[] tooLarge = Arrays.copyOf(CourierApiTest.jpeg(), 5 * 1024 * 1024 + 1);
        upload(courier, parcel, tooLarge)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value("Use a photo up to 5 MB."));
        mvc.perform(multipart(COURIER + "/" + parcel + "/proof").cookie(courier.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value("Add a photo of the signed delivery slip."));

        // A JPEG with EXIF (camera, GPS): stored without it.
        byte[] withExif = ProofImagesFixtures.jpegWithExif(CourierApiTest.jpeg());
        UUID photo = UUID.fromString(read(upload(courier, parcel, withExif).andExpect(status().isCreated()))
                .get("proofPhotoId").asString());
        deliverWith(courier, parcel, photo).andExpect(status().isOk());
        byte[] stored = deliveries.findProofPhoto(parcel).orElseThrow().content();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("GPS");
        assertThat(stored.length).isLessThan(withExif.length);
    }

    @Test
    void parcelsDeliveredBeforeProofPhotosStillReadFine() throws Exception {
        UUID legacy = gampahaParcel();
        // As a parcel delivered before this feature: DELIVERED with a name, no proof photo (the migration adds only a
        // new table; existing rows are untouched and valid).
        jdbc.update("update delivery.parcels set status = 'DELIVERED', proof_recipient_name = 'Old Delivery',"
                + " proof_photo_id = null where id = ?", legacy);

        assertThat(parcel(legacy).status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(parcel(legacy).hasProofPhoto()).isFalse();
        assertThat(deliveries.findProofPhoto(legacy)).isEmpty();
        JsonNode customerView = read(mvc.perform(get("/api/v1/deliveries/" + legacy).cookie(customer))
                .andExpect(status().isOk()));
        // The customer's parcel page answers { "delivery": {...}, "history": [...] }.
        JsonNode proof = customerView.get("delivery").get("proof");
        assertThat(proof.get("recipientName").asString()).isEqualTo("Old Delivery");
        assertThat(proof.get("hasPhoto").asBoolean()).isFalse();
    }

    @Test
    void pickupFailedReturnAndUnableToCollectNeedNoPhoto() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("GAMPAHA");
        UUID failing = gampahaParcel();
        UUID notTaken = gampahaParcel();
        pickupTask(pickupCourier, "GAMPAHA", failing, notTaken);
        moveTo(pickupCourier, failing, "PICKED_UP").andExpect(status().isOk());
        handOver(pickupCourier, failing, "S. Silva").andExpect(status().isOk());
        unableToCollect(pickupCourier, notTaken, "SENDER_UNAVAILABLE").andExpect(status().isOk());

        deliveryTask(deliveryCourier, "GAMPAHA", failing);
        moveTo(deliveryCourier, failing, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        changeStatus(deliveryCourier, failing, Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE"))
                .andExpect(status().isOk());
        handOver(deliveryCourier, failing, "M. Perera")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.outcome").value("RETURNED"))
                .andExpect(jsonPath("$.completed").value(true));
        assertThat(deliveries.findProofPhoto(failing)).isEmpty();
    }
}
