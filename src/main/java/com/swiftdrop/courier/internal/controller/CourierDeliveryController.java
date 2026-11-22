package com.swiftdrop.courier.internal.controller;

import com.swiftdrop.courier.internal.application.CourierDeliveryService;
import com.swiftdrop.courier.internal.application.HistoryFilter;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.courier.internal.dto.CourierDeliveryDetailResponse;
import com.swiftdrop.courier.internal.dto.CourierDeliveryResponse;
import com.swiftdrop.courier.internal.dto.CourierTodayResponse;
import com.swiftdrop.courier.internal.dto.ProofUploadResponse;
import com.swiftdrop.courier.internal.dto.StatusChangeRequest;
import com.swiftdrop.courier.internal.dto.UnableToCollectRequest;
import com.swiftdrop.delivery.ProofPhoto;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The logged-in courier's parcels, one at a time (a dispatcher may group several in one task). COURIER only; other
 * couriers' parcels answer 404.
 */
@RestController
@RequestMapping("/courier/deliveries")
@PreAuthorize("hasRole('COURIER')")
@Tag(name = "Courier - deliveries", description = "Today's parcels, status updates, Unable to Collect and the "
        + "history of completed parcels (COURIER only)")
public class CourierDeliveryController {

    private final CourierDeliveryService courierDeliveries;

    public CourierDeliveryController(CourierDeliveryService courierDeliveries) {
        this.courierDeliveries = courierDeliveries;
    }

    @GetMapping("/today")
    @Operation(summary = "My active parcels, plus the ones I completed today (Sri Lankan time)")
    public CourierTodayResponse today(@AuthenticationPrincipal Jwt jwt) {
        CourierToday today = courierDeliveries.today(CurrentUser.id(jwt));
        return new CourierTodayResponse(today.date(), today.toDo(), today.done(),
                today.parcels().stream().map(item -> CourierDeliveryResponse.from(item.parcel(), item.task())).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "Parcels I completed (pickups and deliveries), newest first")
    public PageResponse<CourierDeliveryResponse> history(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) String trackingNumber,
                                                         @RequestParam(required = false) String type,
                                                         @RequestParam(required = false) String outcome,
                                                         @RequestParam(required = false) String completedFrom,
                                                         @RequestParam(required = false) String completedTo) {
        HistoryFilter filter = HistoryFilter.of(type, outcome, completedFrom, completedTo);
        return PageResponse.from(courierDeliveries.history(CurrentUser.id(jwt), page, size, trackingNumber, filter),
                item -> CourierDeliveryResponse.from(item.parcel(), item.task()));
    }

    @GetMapping("/{parcelId}")
    @Operation(summary = "One of my parcels with its history")
    public CourierDeliveryDetailResponse detail(@PathVariable UUID parcelId, @AuthenticationPrincipal Jwt jwt) {
        return toResponse(courierDeliveries.detail(CurrentUser.id(jwt), parcelId));
    }

    @GetMapping("/{parcelId}/history/{assignmentId}")
    @Operation(summary = "One finished entry of my history: this parcel's step in that task, read-only, with the "
            + "parcel's history up to when that step finished")
    public CourierDeliveryDetailResponse historyEntry(@PathVariable UUID parcelId, @PathVariable UUID assignmentId,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return toResponse(courierDeliveries.historyEntry(CurrentUser.id(jwt), parcelId, assignmentId));
    }

    @PostMapping("/{parcelId}/status")
    @Operation(summary = "Move one of my parcels to its next status (with a recipient name, a failure reason, or who "
            + "received it at the branch)")
    public CourierDeliveryDetailResponse changeStatus(@PathVariable UUID parcelId,
                                                      @Valid @RequestBody StatusChangeRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return toResponse(inOneStep(() -> courierDeliveries.changeStatus(CurrentUser.id(jwt), parcelId, request)));
    }

    @PostMapping("/{parcelId}/unable-to-collect")
    @Operation(summary = "I couldn't take this parcel (pickup: not collected, delivery: not taken out), with the "
            + "reason")
    public CourierDeliveryDetailResponse unableToCollect(@PathVariable UUID parcelId,
                                                         @Valid @RequestBody UnableToCollectRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return toResponse(inOneStep(() -> courierDeliveries.unableToCollect(CurrentUser.id(jwt), parcelId, request)));
    }

    @PostMapping(path = "/{parcelId}/proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload the photo of the delivery slip the recipient signed, for a parcel I'm delivering "
            + "(multipart field 'file': JPEG, PNG or WebP up to 5 MB). Send the returned proofPhotoId with DELIVERED.")
    public ProofUploadResponse uploadProof(@PathVariable UUID parcelId,
                                           @RequestParam(name = "file", required = false) MultipartFile file,
                                           @AuthenticationPrincipal Jwt jwt) throws IOException {
        byte[] content = file == null ? null : file.getBytes();
        return ProofUploadResponse.from(courierDeliveries.uploadProof(CurrentUser.id(jwt), parcelId, content));
    }

    @GetMapping("/{parcelId}/proof")
    @Operation(summary = "The proof-of-delivery photo of a parcel I delivered (private: never cached or shared)")
    public ResponseEntity<byte[]> proof(@PathVariable UUID parcelId, @AuthenticationPrincipal Jwt jwt) {
        ProofPhoto photo = courierDeliveries.proof(CurrentUser.id(jwt), parcelId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("proof-of-delivery." + extension(photo.contentType())).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.content());
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static CourierDeliveryDetailResponse toResponse(ParcelDetail detail) {
        return CourierDeliveryDetailResponse.from(detail.parcel(), detail.history(), detail.task());
    }

    /**
     * Runs one write. Someone else changed the task or the parcel at the same time (optimistic lock, possibly only at
     * commit, after the service returned): 409 ASSIGNMENT_CHANGED, so the courier refreshes, instead of a 500.
     * Nothing was saved.
     */
    private static <T> T inOneStep(Supplier<T> write) {
        try {
            return write.get();
        } catch (OptimisticLockingFailureException ex) {
            throw new ConflictException(ErrorCode.ASSIGNMENT_CHANGED);
        }
    }
}
