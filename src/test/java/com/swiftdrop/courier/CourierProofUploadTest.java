package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ProofPhotoRef;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The courier module's side of the upload, with delivery mocked: task checks, and what is handed to delivery. */
class CourierProofUploadTest extends CourierApiTest {

    @Test
    void theRealContentTypeIsStoredWhateverTheBrowserClaims() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));
        UUID photoId = UUID.randomUUID();
        when(delivery.storeProofPhoto(eq(parcel.id()), eq(courier.id()), any(), any()))
                .thenReturn(new ProofPhotoRef(photoId, "image/png", 120, Instant.now()));

        mvc.perform(multipart(BASE + "/{id}/proof", parcel.id())
                        .file(new MockMultipartFile("file", "slip.jpg", "image/jpeg", image("png")))
                        .cookie(courier.cookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proofPhotoId").value(photoId.toString()));

        verify(delivery).storeProofPhoto(eq(parcel.id()), eq(courier.id()), eq("image/png"), any());
    }

    @Test
    void aClosedTaskTakesNoProof() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY);
        AssignmentView task = givenTask(courier.id(), AssignmentType.DELIVERY, step(parcel, ItemOutcome.LOADED));
        replaceTask(new AssignmentView(task.id(), task.courierId(), task.type(), AssignmentStatus.CANCELLED,
                task.originBranch(), task.destinationBranch(), task.scheduledFor(), task.note(), task.assignedAt(),
                task.startedAt(), null, Instant.now(), task.items(), task.version() + 1));

        postProof(parcel.id(), jpeg(), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        verify(delivery, never()).storeProofPhoto(any(), any(), any(), any());
    }

    @Test
    void aParcelThatIsNotYoursIsNotFoundAndNothingIsStored() throws Exception {
        Courier courier = registerCourier();
        Courier other = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));

        postProof(parcel.id(), jpeg(), other.cookie()).andExpect(status().isNotFound());
        verify(delivery, never()).storeProofPhoto(any(), any(), any(), any());
    }

    @Test
    void anInvalidImageIsRefusedBeforeTheParcelIsLookedUp() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));

        postProof(parcel.id(), image("gif"), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value("Use a JPEG, PNG or WebP photo."));
        verify(delivery, never()).storeProofPhoto(any(), any(), any(), any());
    }
}
