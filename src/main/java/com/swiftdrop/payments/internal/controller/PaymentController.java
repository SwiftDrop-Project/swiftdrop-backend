package com.swiftdrop.payments.internal.controller;

import com.swiftdrop.payments.internal.application.PaymentService;
<<<<<<< HEAD
import com.swiftdrop.payments.internal.dto.CardCheckResponse;
=======
>>>>>>> 53298e6f2d7b071eaf14fe6fefb651418225b004
import com.swiftdrop.payments.internal.dto.CardPaymentRequest;
import com.swiftdrop.payments.internal.dto.PaymentResponse;
import com.swiftdrop.payments.internal.dto.PaymentSummaryResponse;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The logged-in customer's payments: one per delivery, the totals, and paying an online delivery with a mock card.
 * The customer always comes from the login.
 */
@RestController
@RequestMapping("/payments")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Payments", description = "One payment record per delivery; mock card payments")
public class PaymentController {

    static final int MAX_PAGE_SIZE = 100;

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "My payments, one per delivery, newest booking first")
    public PageResponse<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        return PageResponse.from(payments.list(CurrentUser.id(jwt), page, size));
    }

    @GetMapping("/summary")
    @Operation(summary = "Paid to date and what is still due (cash for the courier, online to pay here)")
    public PaymentSummaryResponse summary(@AuthenticationPrincipal Jwt jwt) {
        return payments.summary(CurrentUser.id(jwt));
    }

    @PostMapping("/{id}/card")
    @Operation(summary = "Pay an online delivery with a mock card (test cards only; nothing is charged)")
    public PaymentResponse payByCard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody CardPaymentRequest card) {
        return payments.payByCard(CurrentUser.id(jwt), id, card);
    }
<<<<<<< HEAD

    @PostMapping("/card-check")
    @Operation(summary = "Payment gateway step of Create delivery: check a mock card (nothing is charged or stored)")
    public CardCheckResponse checkCard(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CardPaymentRequest card) {
        return payments.checkCard(card);
    }

    @PostMapping("/parcels/{parcelId}/card")
    @Operation(summary = "Pay the fee of one of my online deliveries by mock card, found by the delivery")
    public PaymentResponse payParcelByCard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID parcelId,
                                           @Valid @RequestBody CardPaymentRequest card) {
        return payments.payParcelByCard(CurrentUser.id(jwt), parcelId, card);
    }
=======
>>>>>>> 53298e6f2d7b071eaf14fe6fefb651418225b004
}
