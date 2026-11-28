package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.delivery.internal.dto.ReceiptResponse;
import com.swiftdrop.delivery.internal.receipt.ReceiptService;
import com.swiftdrop.delivery.internal.receipt.ReceiptService.ReceiptPdf;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The receipt of one of the customer's own deliveries: as JSON for the preview on "My deliveries", and as a PDF to
 * download. Someone else's delivery, a merchant order and an unknown id are 404 NOT_FOUND.
 */
@RestController
@RequestMapping("/deliveries/{id}/receipt")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Deliveries")
public class ReceiptController {

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping
    @Operation(summary = "The receipt of one of my deliveries: the fee as booked, line by line")
    public ReceiptResponse receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return receipts.forCustomer(CurrentUser.id(jwt), id);
    }

    /**
     * A download (Content-Disposition: attachment); never cached, as it holds the contacts' personal data. No
     * {@code produces}: an error (e.g. 404) must still go out as the usual JSON error body.
     */
    @GetMapping("/pdf")
    @Operation(summary = "The receipt of one of my deliveries as a PDF to download")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ReceiptPdf pdf = receipts.pdfForCustomer(CurrentUser.id(jwt), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.content());
    }
}
