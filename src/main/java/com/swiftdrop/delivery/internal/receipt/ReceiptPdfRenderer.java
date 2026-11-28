package com.swiftdrop.delivery.internal.receipt;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.Line;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.PaymentStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Draws a receipt on one A4 page with PDFBox and the standard Helvetica font (nothing to embed). Dates are Sri Lankan
 * time, amounts "Rs. 1,250.00", phones "+94 77 123 4567" and tracking numbers "SD 2611 0031 52", the same as the
 * frontend shows them. Helvetica covers Latin text only: any other character (e.g. a name in Sinhala or Tamil) is
 * printed as "?" rather than failing the download.
 */
@Component
public class ReceiptPdfRenderer {

    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(SRI_LANKA);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a",
            Locale.ENGLISH).withZone(SRI_LANKA);

    private static final Map<ParcelStatus, String> STATUS = Map.of(
            ParcelStatus.CREATED, "Pending pickup",
            ParcelStatus.PICKED_UP, "Picked up",
            ParcelStatus.AT_BRANCH, "At branch",
            ParcelStatus.IN_TRANSIT, "In transit",
            ParcelStatus.OUT_FOR_DELIVERY, "Out for delivery",
            ParcelStatus.DELIVERED, "Delivered",
            ParcelStatus.FAILED, "Delivery failed");

    private static final Map<PaymentMethod, String> PAYMENT = Map.of(
            PaymentMethod.ONLINE, "Online payment",
            PaymentMethod.AT_PICKUP, "Cash at pickup",
            PaymentMethod.AT_DROP_OFF, "Cash at drop-off");

    // Page geometry (points) and colours: dark grey text, mid grey labels, light grey rules, SwiftDrop orange.
    private static final float MARGIN = 56;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final Color TEXT = new Color(17, 24, 39);
    private static final Color MUTED = new Color(75, 85, 99);
    private static final Color RULE = new Color(229, 231, 235);
    private static final Color BRAND = new Color(194, 65, 12);

    private final Clock clock;

    public ReceiptPdfRenderer() {
        this(Clock.systemUTC());
    }

    ReceiptPdfRenderer(Clock clock) {
        this.clock = clock;
    }

    /** The receipt as PDF bytes. */
    public byte[] render(ReceiptResponse receipt) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            describe(document, receipt);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                new Writer(content, PDRectangle.A4.getHeight() - MARGIN).write(receipt, clock.instant());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The receipt PDF couldn't be created", e);
        }
    }

    private void describe(PDDocument document, ReceiptResponse receipt) {
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle("SwiftDrop delivery receipt " + trackingNumber(receipt.trackingNumber()));
        info.setAuthor("SwiftDrop");
        info.setCreator("SwiftDrop");
        Calendar created = GregorianCalendar.from(clock.instant().atZone(SRI_LANKA));
        created.setTimeZone(TimeZone.getTimeZone(SRI_LANKA));
        info.setCreationDate(created);
    }

    /** Writes the page top to bottom; {@code y} is the baseline of the next line. */
    private static final class Writer {

        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDPageContentStream content;
        private float y;

        Writer(PDPageContentStream content, float top) {
            this.content = content;
            this.y = top;
        }

        void write(ReceiptResponse receipt, Instant issuedAt) throws IOException {
            // Header: brand on the left, document name on the right, an orange rule under both.
            text(bold, 22, BRAND, MARGIN, y - 22, "SwiftDrop");
            textRight(bold, 12, MUTED, MARGIN + WIDTH, y - 18, "Delivery receipt");
            y -= 36;
            rule(BRAND, 1.5f);
            y -= 28;

            row("Tracking number", trackingNumber(receipt.trackingNumber()), true);
            row("Booked", DATE_TIME.format(receipt.bookedAt()), false);
            row("Status", STATUS.getOrDefault(receipt.status(), receipt.status().name()), false);
            y -= 8;
            rule(RULE, 1);
            y -= 24;

            contact("Pickup", receipt.pickup());
            contact("Drop-off", receipt.recipient());
            row("Parcel", parcel(receipt), false);
            y -= 8;
            rule(RULE, 1);
            y -= 24;

            for (Line line : receipt.lines()) {
                row(line.label(), money(line.amount()), false);
            }
            y -= 4;
            rule(RULE, 1);
            y -= 22;
            text(bold, 13, TEXT, MARGIN, y, "Total");
            textRight(bold, 13, TEXT, MARGIN + WIDTH, y, money(receipt.total()));
            y -= 28;

            if (receipt.paymentMethod() != null) {
                row("Payment", payment(receipt.paymentMethod(), receipt.paymentStatus()), false);
            }
            if (receipt.codAmount() != null) {
                row("Cash on delivery", money(receipt.codAmount()) + " collected from the recipient for you", false);
            }

            float footer = MARGIN + 24;
            text(regular, 9, MUTED, MARGIN, footer,
                    "Issued on " + DATE.format(issuedAt) + ". Amounts in Sri Lankan rupees ("
                            + receipt.currency() + ").");
            text(regular, 9, MUTED, MARGIN, footer - 13,
                    "This receipt shows the fee fixed when the delivery was booked.");
        }

        /** A label on the left and its value on the right, the value wrapped onto more lines when it is long. */
        private void row(String label, String value, boolean strong) throws IOException {
            text(regular, 10.5f, MUTED, MARGIN, y, label);
            List<String> lines = wrap(strong ? bold : regular, 10.5f, value, WIDTH * 0.62f);
            for (String line : lines) {
                textRight(strong ? bold : regular, 10.5f, TEXT, MARGIN + WIDTH, y, line);
                y -= 15;
            }
            y -= 5;
        }

        /** "Pickup" above the name, the address, the city and district, and the phone. */
        private void contact(String label, DeliveryContact contact) throws IOException {
            text(regular, 10.5f, MUTED, MARGIN, y, label);
            y -= 15;
            text(bold, 10.5f, TEXT, MARGIN, y, contact.name());
            y -= 15;
            for (String line : wrap(regular, 10.5f, contact.addressLine(), WIDTH)) {
                text(regular, 10.5f, TEXT, MARGIN, y, line);
                y -= 15;
            }
            text(regular, 10.5f, TEXT, MARGIN, y, contact.city() + ", " + contact.district().displayName());
            y -= 15;
            text(regular, 10.5f, TEXT, MARGIN, y, phone(contact.phone()));
            y -= 22;
        }

        private void rule(Color color, float width) throws IOException {
            content.setStrokingColor(color);
            content.setLineWidth(width);
            content.moveTo(MARGIN, y);
            content.lineTo(MARGIN + WIDTH, y);
            content.stroke();
        }

        private void text(PDType1Font font, float size, Color color, float x, float baseline, String value)
                throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, baseline);
            content.showText(printable(font, value));
            content.endText();
        }

        private void textRight(PDType1Font font, float size, Color color, float right, float baseline, String value)
                throws IOException {
            String printable = printable(font, value);
            text(font, size, color, right - width(font, size, printable), baseline, printable);
        }

        /** Word-wraps to the width; a single word longer than the width stays on its own line. */
        private List<String> wrap(PDType1Font font, float size, String value, float maxWidth) throws IOException {
            String printable = printable(font, value);
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : printable.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && width(font, size, candidate) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
            return lines;
        }

        private static float width(PDType1Font font, float size, String value) throws IOException {
            return font.getStringWidth(value) / 1000 * size;
        }
    }

    /** The characters Helvetica (WinAnsi) can print; any other becomes "?". Line breaks and tabs become spaces. */
    static String printable(PDType1Font font, String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String character = Character.isWhitespace(codePoint) ? " " : Character.toString(codePoint);
            try {
                font.encode(character);
                out.append(character);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }

    /** "SD2611003152" → "SD 2611 0031 52"; any other shape is printed as it is. */
    static String trackingNumber(String value) {
        if (value == null || !value.matches("SD\\d{10}")) {
            return value;
        }
        return value.substring(0, 2) + " " + value.substring(2, 6) + " " + value.substring(6, 10) + " "
                + value.substring(10);
    }

    /** 1250 → "Rs. 1,250.00" */
    static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "Rs. " + format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** "+94771234567" → "+94 77 123 4567"; any other shape is printed as it is. */
    static String phone(String value) {
        if (value == null || !value.matches("\\+94\\d{9}")) {
            return value;
        }
        return "+94 " + value.substring(3, 5) + " " + value.substring(5, 8) + " " + value.substring(8);
    }

    /** "2 kg (charged for 2 kg) · 30 × 20 × 15 cm · Bike" with whatever the parcel has. */
    static String parcel(ReceiptResponse receipt) {
        List<String> parts = new ArrayList<>();
        String weight = receipt.weightKg().stripTrailingZeros().toPlainString() + " kg";
        if (receipt.billableWeightKg() != null
                && receipt.weightKg().compareTo(BigDecimal.valueOf(receipt.billableWeightKg())) != 0) {
            weight += " (charged for " + receipt.billableWeightKg() + " kg)";
        }
        parts.add(weight);
        ParcelSize size = receipt.size();
        if (size != null) {
            parts.add(size.lengthCm() + " × " + size.widthCm() + " × " + size.heightCm() + " cm");
        }
        if (receipt.vehicle() != null) {
            String vehicle = receipt.vehicle().replace('_', ' ').toLowerCase(Locale.ENGLISH);
            parts.add(Character.toUpperCase(vehicle.charAt(0)) + vehicle.substring(1));
        }
        return String.join(" · ", parts);
    }

    /** "Cash at pickup · Paid" */
    static String payment(PaymentMethod method, PaymentStatus status) {
        String label = PAYMENT.get(method);
        if (status == null) {
            return label;
        }
        return label + " · " + (status == PaymentStatus.PAID ? "Paid" : "Due");
    }
}
