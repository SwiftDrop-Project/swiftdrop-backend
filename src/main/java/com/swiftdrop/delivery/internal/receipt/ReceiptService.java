package com.swiftdrop.delivery.internal.receipt;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.Line;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.PaymentStatus;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receipt of a customer's own delivery, as JSON or as a PDF. The fee comes from the parcel as it was booked (its
 * stored fee lines), never from a new quote, so the receipt doesn't change when the rate card does.
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private static final Map<String, String> LABELS = Map.of(
            "BASE", "Base fee",
            "PICKUP", "Pickup",
            "DELIVERY", "Drop-off",
            "LINEHAUL", "Between branches",
            "COD", "Cash on delivery handling");

    private final ParcelRepository parcels;
    private final DeliveryApi deliveries;
    private final JsonMapper jsonMapper;
    private final ReceiptPdfRenderer renderer;

    public ReceiptService(ParcelRepository parcels, DeliveryApi deliveries, JsonMapper jsonMapper,
                          ReceiptPdfRenderer renderer) {
        this.parcels = parcels;
        this.deliveries = deliveries;
        this.jsonMapper = jsonMapper;
        this.renderer = renderer;
    }

    /**
     * The receipt of one of the customer's deliveries. Someone else's delivery, a merchant order and an unknown id
     * are all 404 NOT_FOUND, like GET /deliveries/{id}, so nobody can find out which ids exist. The snapshot comes
     * from {@link DeliveryApi#findById}, so it carries the recorded fee payment ({@code feePaymentStatus}).
     */
    @Transactional(readOnly = true)
    public ReceiptResponse forCustomer(UUID customerId, UUID parcelId) {
        Parcel parcel = parcels.findById(parcelId)
                .filter(found -> customerId.equals(found.toSnapshot().customerId()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        DeliverySnapshot snapshot = deliveries.findById(parcelId).orElseGet(parcel::toSnapshot);
        return receipt(snapshot, parcel.feeBreakdownJson());
    }

    /** The same receipt as a PDF document. */
    public ReceiptPdf pdfForCustomer(UUID customerId, UUID parcelId) {
        ReceiptResponse receipt = forCustomer(customerId, parcelId);
        return new ReceiptPdf(fileName(receipt.trackingNumber()), renderer.render(receipt));
    }

    /** A PDF receipt and the name to save it as. */
    public record ReceiptPdf(String fileName, byte[] content) {
    }

    ReceiptResponse receipt(DeliverySnapshot parcel, String breakdownJson) {
        return new ReceiptResponse(parcel.trackingNumber(), parcel.status(), parcel.createdAt(), parcel.pickup(),
                parcel.recipient(), parcel.weightKg(), parcel.billableWeightKg(), parcel.size(), parcel.vehicle(),
                lines(breakdownJson, parcel.feeAmount(), parcel.billableWeightKg()), parcel.feeAmount(),
                parcel.currency(), parcel.codAmount(), parcel.paymentMethod(),
                paymentStatus(parcel.feePaymentStatus()));
    }

    /**
     * The stored fee lines with their labels. A parcel booked before pricing (no lines), or lines that can't be read,
     * get one "Delivery fee" line with the whole fee, so the receipt always adds up.
     */
    List<Line> lines(String breakdownJson, BigDecimal total, Integer billableWeightKg) {
        List<Line> flat = List.of(new Line("FEE", "Delivery fee", total));
        if (breakdownJson == null || breakdownJson.isBlank()) {
            return flat;
        }
        try {
            List<Line> lines = new ArrayList<>();
            for (JsonNode node : jsonMapper.readTree(breakdownJson)) {
                String component = node.path("component").asString();
                // The JSON number may come back as 650.0: rupees always have two decimals.
                BigDecimal amount = new BigDecimal(node.path("amount").asString()).setScale(2, RoundingMode.HALF_UP);
                lines.add(new Line(component, label(component, billableWeightKg), amount));
            }
            return lines.isEmpty() ? flat : lines;
        } catch (JacksonException | NumberFormatException e) {
            // Never log the JSON itself: only that it couldn't be read.
            log.warn("A parcel's fee lines couldn't be read; the receipt shows the total only");
            return flat;
        }
    }

    /** "Extra weight (1 kg)" for the kilograms above the first; the other components have fixed names. */
    static String label(String component, Integer billableWeightKg) {
        if ("WEIGHT".equals(component)) {
            return billableWeightKg != null && billableWeightKg > 1
                    ? "Extra weight (" + (billableWeightKg - 1) + " kg)"
                    : "Extra weight";
        }
        return LABELS.getOrDefault(component, "Other charges");
    }

    /**
     * The one rule for "is the fee paid" is delivery's {@link FeePaymentStatus}: PAID once a payment is recorded (a
     * courier collected the cash, or the payments module recorded a verified online payment), DUE while it is still
     * awaited, and null for parcels booked before payment methods (nothing is known).
     */
    static PaymentStatus paymentStatus(FeePaymentStatus feePaymentStatus) {
        if (feePaymentStatus == null || feePaymentStatus == FeePaymentStatus.NOT_RECORDED) {
            return null;
        }
        return feePaymentStatus.isPaid() ? PaymentStatus.PAID : PaymentStatus.DUE;
    }

    /** "SwiftDrop-receipt-SD2611003152.pdf" */
    static String fileName(String trackingNumber) {
        return "SwiftDrop-receipt-" + trackingNumber + ".pdf";
    }
}
