package com.swiftdrop.delivery.internal.receipt;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.Line;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.PaymentStatus;
import com.swiftdrop.shared.reference.District;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF is a real one-page document with the receipt's text, formatted as the frontend shows it. */
class ReceiptPdfRendererTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-22T06:30:00Z");
    private final ReceiptPdfRenderer renderer =
            new ReceiptPdfRenderer(Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

    @Test
    void theReceiptIsAOnePagePdfWithEveryLineAndTheTotal() throws IOException {
        byte[] pdf = renderer.render(receipt("Nimali Perera", PaymentMethod.AT_PICKUP, PaymentStatus.PAID, null));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getDocumentInformation().getTitle())
                    .isEqualTo("SwiftDrop delivery receipt SD 2611 0031 52");
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(
                    "SwiftDrop", "Delivery receipt",
                    "SD 2611 0031 52",
                    "9 Nov 2026, 10:42 AM",
                    "Delivered",
                    "Nimali Perera", "24 Temple Road", "Gampaha, Gampaha", "+94 77 123 4567",
                    "Kamal Silva", "118 Galle Road", "Colombo 03, Colombo",
                    "2 kg · 30 × 20 × 15 cm · Bike",
                    "Base fee", "Rs. 650.00",
                    "Extra weight (1 kg)", "Rs. 330.00",
                    "Total", "Rs. 980.00",
                    "Cash at pickup · Paid",
                    "Issued on 22 Nov 2026");
            assertThat(text).doesNotContain("Cash on delivery");
        }
    }

    @Test
    void cashOnDeliveryIsShownApartFromTheFee() throws IOException {
        byte[] pdf = renderer.render(receipt("Nimali Perera", PaymentMethod.AT_DROP_OFF, PaymentStatus.DUE,
                new BigDecimal("4500")));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Cash at drop-off · Due",
                    "Rs. 4,500.00 collected from the recipient for you");
        }
    }

    @Test
    void anOnlinePaymentShowsNoPaidOrDueAndOldParcelsShowNoPaymentLine() throws IOException {
        String online = text(renderer.render(receipt("Nimali Perera", PaymentMethod.ONLINE, null, null)));
        assertThat(online).contains("Online payment").doesNotContain("Paid").doesNotContain("Due");

        String old = text(renderer.render(receipt("Nimali Perera", null, null, null)));
        assertThat(old).doesNotContain("Payment");
    }

    @Test
    void lettersHelveticaCannotPrintBecomeQuestionMarksInsteadOfFailing() throws IOException {
        byte[] pdf = renderer.render(receipt("නිමලි Perera", PaymentMethod.ONLINE, null, null));

        assertThat(text(pdf)).contains("????? Perera");
        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        assertThat(ReceiptPdfRenderer.printable(helvetica, "Line one\nline two→")).isEqualTo("Line one line two?");
        assertThat(ReceiptPdfRenderer.printable(helvetica, null)).isEmpty();
    }

    @Test
    void numbersAreFormattedLikeTheFrontend() {
        assertThat(ReceiptPdfRenderer.trackingNumber("SD2611003152")).isEqualTo("SD 2611 0031 52");
        assertThat(ReceiptPdfRenderer.trackingNumber("OTHER")).isEqualTo("OTHER");
        assertThat(ReceiptPdfRenderer.money(new BigDecimal("1250"))).isEqualTo("Rs. 1,250.00");
        assertThat(ReceiptPdfRenderer.money(new BigDecimal("980.005"))).isEqualTo("Rs. 980.01");
        assertThat(ReceiptPdfRenderer.phone("+94771234567")).isEqualTo("+94 77 123 4567");
        assertThat(ReceiptPdfRenderer.phone("0771234567")).isEqualTo("0771234567");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static ReceiptResponse receipt(String senderName, PaymentMethod method, PaymentStatus paymentStatus,
                                           BigDecimal codAmount) {
        return new ReceiptResponse("SD2611003152", ParcelStatus.DELIVERED, Instant.parse("2026-11-09T05:12:00Z"),
                new DeliveryContact(senderName, "+94771234567", "24 Temple Road", "Gampaha", District.GAMPAHA),
                new DeliveryContact("Kamal Silva", "+94719876543", "118 Galle Road", "Colombo 03", District.COLOMBO),
                new BigDecimal("2.00"), 2, new ParcelSize(30, 20, 15, false), "BIKE",
                List.of(new Line("BASE", "Base fee", new BigDecimal("650.00")),
                        new Line("WEIGHT", "Extra weight (1 kg)", new BigDecimal("330.00"))),
                new BigDecimal("980.00"), "LKR", codAmount, method, paymentStatus);
    }
}
