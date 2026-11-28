package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.delivery.internal.receipt.ReceiptService;
import com.swiftdrop.delivery.internal.receipt.ReceiptService.ReceiptPdf;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The receipt endpoints without Spring: the customer comes from the token, and the PDF is a download. */
class ReceiptControllerTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Jwt CUSTOMER = Jwt.withTokenValue("token").header("alg", "none")
            .subject(CUSTOMER_ID.toString()).build();

    private final ReceiptService receipts = mock(ReceiptService.class);
    private final ReceiptController controller = new ReceiptController(receipts);

    @Test
    void theReceiptIsTheLoggedInCustomers() {
        UUID id = UUID.randomUUID();

        controller.receipt(CUSTOMER, id);

        verify(receipts).forCustomer(CUSTOMER_ID, id);
    }

    @Test
    void thePdfIsAnUncachedDownloadNamedAfterTheTrackingNumber() {
        UUID id = UUID.randomUUID();
        byte[] content = {'%', 'P', 'D', 'F'};
        when(receipts.pdfForCustomer(CUSTOMER_ID, id))
                .thenReturn(new ReceiptPdf("SwiftDrop-receipt-SD2611003152.pdf", content));

        ResponseEntity<byte[]> response = controller.pdf(CUSTOMER, id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"SwiftDrop-receipt-SD2611003152.pdf\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(content);
    }
}
