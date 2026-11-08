package com.swiftdrop.courier;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourierStatusUpdateTest extends CourierApiTest {

    // ---- Allowed changes ----

    @Test
    void pickUpSendsTheCourierAsActorAndTheTrimmedNote() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));
        DeliverySnapshot pickedUp = withStatus(parcel, ParcelStatus.PICKED_UP);
        when(delivery.changeStatus(any())).thenReturn(pickedUp);
        when(delivery.history(parcel.id())).thenReturn(List.of(
                new ParcelStatusEntry(ParcelStatus.PICKED_UP, "Collected from front desk", null, "COURIER",
                        Instant.parse("2026-11-06T03:00:00Z"))));

        postStatus(parcel.id(), Map.of("status", "PICKED_UP", "note", "  Collected from front desk  "), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.stop").value("DELIVERY"))
                .andExpect(jsonPath("$.nextStatuses[0]").value("AT_BRANCH"))
                .andExpect(jsonPath("$.history[0].status").value("PICKED_UP"))
                .andExpect(jsonPath("$.history[0].note").value("Collected from front desk"))
                .andExpect(jsonPath("$.history[0].actorRole").doesNotExist());

        ChangeStatusCommand command = sentCommand();
        assertThat(command.parcelId()).isEqualTo(parcel.id());
        assertThat(command.toStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(command.actorId()).isEqualTo(courier.id());
        assertThat(command.actorRole()).isEqualTo("COURIER");
        assertThat(command.note()).isEqualTo("Collected from front desk");
        assertThat(command.recipientName()).isNull();
        assertThat(command.failureReason()).isNull();
        assertThat(command.proofPhotoId()).isNull();
    }

    @Test
    void deliveredSendsTheRecipientNameAndShowsTheProof() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));
        when(delivery.changeStatus(any())).thenReturn(withStatus(parcel, ParcelStatus.DELIVERED));

        postStatus(parcel.id(), Map.of("status", "DELIVERED", "recipientName", " Nimal Perera "), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextStatuses").isEmpty())
                .andExpect(jsonPath("$.proof.recipientName").value("Nimal Perera"))
                .andExpect(jsonPath("$.proof.hasPhoto").value(false))
                .andExpect(jsonPath("$.failureReason").isEmpty());

        assertThat(sentCommand().recipientName()).isEqualTo("Nimal Perera");
    }

    @Test
    void failedSendsTheReason() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));
        when(delivery.changeStatus(any())).thenReturn(withStatus(parcel, ParcelStatus.FAILED));

        postStatus(parcel.id(), Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE",
                "note", "No answer, called twice"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("RECIPIENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.proof").isEmpty());

        ChangeStatusCommand command = sentCommand();
        assertThat(command.failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(command.note()).isEqualTo("No answer, called twice");
    }

    @Test
    void otherReasonWithANoteIsAccepted() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));
        when(delivery.changeStatus(any())).thenReturn(withStatus(parcel, ParcelStatus.FAILED));

        postStatus(parcel.id(), Map.of("status", "FAILED", "failureReason", "OTHER", "note", "Gate locked"),
                courier.cookie())
                .andExpect(status().isOk());
    }

    // ---- Transitions (409) ----

    @Test
    void skippingAStepIs409AndNothingChanges() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));

        postStatus(parcel.id(), Map.of("status", "DELIVERED", "recipientName", "Nimal Perera"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void aFinishedParcelCannotChangeAgain() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.DELIVERED));

        postStatus(parcel.id(), Map.of("status", "FAILED", "failureReason", "RECIPIENT_REFUSED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void aChangeThatDeliveryRefusesIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));
        when(delivery.changeStatus(any()))
                .thenThrow(new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION));

        postStatus(parcel.id(), Map.of("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    // ---- Known until PR (c) ----

    /**
     * Documents current behaviour, not the wanted one. nextStatuses offers AT_BRANCH after PICKED_UP, but this
     * endpoint has no branch field, so ChangeStatusCommand rejects the change (branch is required for AT_BRANCH) and
     * the courier gets a 500. Nothing changes. PR (c) replaces this endpoint with assignment handovers, which send the
     * branch; delete this test then.
     */
    @Test
    void atBranchWithoutABranchIs500AndNothingChanges() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.PICKED_UP));

        postStatus(parcel.id(), Map.of("status", "AT_BRANCH"), courier.cookie())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verify(delivery, never()).changeStatus(any());
    }

    // ---- Request rules (400) ----

    @Test
    void failedWithoutAReasonIs400() throws Exception {
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "FAILED"),
                "failureReason", "Select why the delivery failed.");
    }

    @Test
    void otherReasonWithoutANoteIs400() throws Exception {
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "FAILED", "failureReason", "OTHER",
                "note", "  "), "note", "Describe what went wrong.");
    }

    @Test
    void deliveredWithoutARecipientNameIs400() throws Exception {
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "DELIVERED"),
                "recipientName", "Enter the name of the person who received the parcel.");
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "DELIVERED", "recipientName", " N "),
                "recipientName", "Enter the name of the person who received the parcel.");
    }

    @Test
    void recipientNameIsOnlyForDelivered() throws Exception {
        expectFieldError(ParcelStatus.CREATED, Map.of("status", "PICKED_UP", "recipientName", "Nimal Perera"),
                "recipientName", "Only for delivered parcels.");
    }

    @Test
    void failureReasonIsOnlyForFailed() throws Exception {
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "DELIVERED",
                "recipientName", "Nimal Perera", "failureReason", "WRONG_ADDRESS"),
                "failureReason", "Only for failed deliveries.");
    }

    @Test
    void tooLongNoteOrNameIs400() throws Exception {
        expectFieldError(ParcelStatus.CREATED, Map.of("status", "PICKED_UP", "note", "x".repeat(501)),
                "note", "Keep the note under 500 characters.");
        expectFieldError(ParcelStatus.OUT_FOR_DELIVERY, Map.of("status", "DELIVERED", "recipientName", "x".repeat(101)),
                "recipientName", "Enter the name of the person who received the parcel.");
    }

    @Test
    void missingStatusIs400() throws Exception {
        expectFieldError(ParcelStatus.CREATED, Map.of("note", "Collected"), "status", "Select the new status.");
    }

    @Test
    void unknownStatusOrReasonIs400() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));

        postStatusRaw(parcel.id(), "{\"status\":\"LOST\"}", courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").exists());
        postStatusRaw(parcel.id(), "{\"status\":\"FAILED\",\"failureReason\":\"RAIN\"}", courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.failureReason").exists());
        verify(delivery, never()).changeStatus(any());
    }

    private void expectFieldError(ParcelStatus current, Map<String, ?> body, String field, String message)
            throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), current));

        postStatus(parcel.id(), body, courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verify(delivery, never()).changeStatus(any());
    }

    private ChangeStatusCommand sentCommand() {
        ArgumentCaptor<ChangeStatusCommand> captor = ArgumentCaptor.forClass(ChangeStatusCommand.class);
        verify(delivery).changeStatus(captor.capture());
        return captor.getValue();
    }

    /** The parcel after delivery applied the change (proof name / reason as the snapshot builder sets them). */
    private static DeliverySnapshot withStatus(DeliverySnapshot parcel, ParcelStatus status) {
        return parcel(parcel.assignedCourierId(), status, parcel.assignedAt(), Instant.now());
    }
}
