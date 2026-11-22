package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.shared.reference.District;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The delivery fee from the active rate card, exactly as {@code POST /pricing/estimate} works it out. */
@Component
class EstimatePriceTool implements AssistantTool {

    private static final Logger log = LoggerFactory.getLogger(EstimatePriceTool.class);
    private static final BigDecimal MIN_KG = new BigDecimal("0.01");
    private static final BigDecimal MAX_KG = new BigDecimal("50");

    private final PricingApi pricing;

    EstimatePriceTool(PricingApi pricing) {
        this.pricing = pricing;
    }

    @Override
    public String name() {
        return "estimate_price";
    }

    @Override
    public String title() {
        return "Estimate a delivery fee";
    }

    @Override
    public String description() {
        return "Estimate the SwiftDrop delivery fee in Sri Lankan rupees (LKR) for a parcel between two Sri Lankan "
                + "districts. Needs the pickup district, the drop-off district, the weight in kg (0.01-50) and the packed "
                + "length, width and height in cm: the size and weight pick the vehicle (bike, tuk-tuk or lorry) "
                + "whose fee is added for the pickup and the delivery. Never guess a district or a size the user "
                + "hasn't given: ask.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> district = Map.of("type", "string", "enum", Words.districtCodes());
        Map<String, Object> centimetres = Map.of("type", "integer", "minimum", 1, "maximum", 300);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pickupDistrict", withDescription(district, "District the parcel is collected from"));
        properties.put("dropOffDistrict", withDescription(district, "District the parcel is delivered to"));
        properties.put("weightKg", Map.of("type", "number", "minimum", 0.01, "maximum", 50,
                "description", "Weight in kg including packaging"));
        properties.put("lengthCm", withDescription(centimetres, "Packed length in cm"));
        properties.put("widthCm", withDescription(centimetres, "Packed width in cm"));
        properties.put("heightCm", withDescription(centimetres, "Packed height in cm"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("pickupDistrict", "dropOffDistrict", "weightKg", "lengthCm", "widthCm", "heightCm"));
    }

    private static Map<String, Object> withDescription(Map<String, Object> schema, String description) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put("description", description);
        return copy;
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        Optional<District> pickup = Words.district(arguments.path("pickupDistrict").asString(null));
        if (pickup.isEmpty()) {
            return ToolResult.failed("Ask the user which district the parcel is collected from.");
        }
        Optional<District> dropOff = Words.district(arguments.path("dropOffDistrict").asString(null));
        if (dropOff.isEmpty()) {
            return ToolResult.failed("Ask the user which district the parcel goes to.");
        }
        JsonNode weightNode = arguments.path("weightKg");
        BigDecimal weight;
        try {
            weight = weightNode.isNumber() ? weightNode.decimalValue() : new BigDecimal(weightNode.asString("").trim());
        } catch (NumberFormatException missing) {
            return ToolResult.failed("Ask the user how heavy the parcel is, in kg.");
        }
        weight = weight.setScale(2, RoundingMode.HALF_UP);
        if (weight.compareTo(MIN_KG) < 0 || weight.compareTo(MAX_KG) > 0) {
            return ToolResult.failed("SwiftDrop takes parcels from 0.01 kg to 50 kg.");
        }
        Integer length = centimetres(arguments, "lengthCm");
        Integer width = centimetres(arguments, "widthCm");
        Integer height = centimetres(arguments, "heightCm");
        long given = java.util.stream.Stream.of(length, width, height).filter(java.util.Objects::nonNull).count();
        if (given != 3) {
            // Without the size there is no vehicle, so the pickup and delivery fees would be missing.
            return ToolResult.failed("Ask the user for the parcel's size: length, width and height in whole cm.");
        }
        if (Boolean.TRUE.equals(outOfRange(length, width, height))) {
            return ToolResult.failed("Measurements must be whole centimetres from 1 to 300.");
        }

        PriceQuote quote;
        try {
            quote = pricing.quote(new QuoteRequest(pickup.get(), dropOff.get(), weight, length, width, height,
                    false, false));
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            log.warn("Price estimate failed: {}", unavailable.getMessage());
            return ToolResult.failed("Prices aren't available right now. Try the Rates page in a moment.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pickupDistrict", pickup.get().displayName());
        data.put("dropOffDistrict", dropOff.get().displayName());
        data.put("weightKg", weight);
        data.put("status", quote.status().name());
        data.put("currency", quote.currency());
        data.put("total", quote.total());
        data.put("totalText", quote.total() == null ? null : Words.rupees(quote.total()));
        data.put("billableWeightKg", quote.billableWeightKg());
        data.put("vehicle", quote.vehicle() == null ? null : quote.vehicle().name());
        data.put("vehicleText", quote.vehicle() == null ? null : Words.vehicle(quote.vehicle()));
        data.put("sizeCm", length + " x " + width + " x " + height);
        data.put("distanceKm", quote.distanceKm());
        data.put("lines", quote.lines().stream()
                .map(line -> Map.of("component", line.component().name(), "amount", line.amount()))
                .toList());
        data.put("note", switch (quote.status()) {
            case COMPLETE -> "Full estimate.";
            case PROVISIONAL -> "Estimate without the parcel's size: the pickup and delivery charges "
                    + "(by bike, tuk-tuk or lorry) are added once the length, width and height are known.";
            case MANUAL_QUOTE -> "Too big or heavy for a bike, tuk-tuk or lorry on the local legs: it needs a "
                    + "manual quote from SwiftDrop.";
        });
        return ToolResult.of(data, new AssistantCard("PRICE", data));
    }

    private static Integer centimetres(JsonNode arguments, String field) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        String text = node.asString("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static Boolean outOfRange(Integer... values) {
        for (Integer value : values) {
            if (value != null && (value < 1 || value > 300)) {
                return true;
            }
        }
        return false;
    }
}
