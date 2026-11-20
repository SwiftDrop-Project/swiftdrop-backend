package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.tracking.ParcelTracking;
import com.swiftdrop.tracking.TrackingApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Where a parcel is, from its tracking number: the public tracking view, never personal data. */
@Component
class TrackParcelTool implements AssistantTool {

    private final TrackingApi tracking;

    TrackParcelTool(TrackingApi tracking) {
        this.tracking = tracking;
    }

    @Override
    public String name() {
        return "track_parcel";
    }

    @Override
    public String title() {
        return "Track a parcel";
    }

    @Override
    public String description() {
        return "Find where a SwiftDrop parcel is from its tracking number (SD followed by 10 digits, e.g. "
                + "SD2611004817; spaces are fine). Returns its status, the towns it travels between and its steps "
                + "so far, with times in Sri Lankan time. Ask the user for the number if they haven't given it.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("trackingNumber", Map.of(
                        "type", "string",
                        "description", "The tracking number, e.g. SD2611004817")),
                "required", List.of("trackingNumber"));
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        String number = arguments.path("trackingNumber").asString("").trim();
        if (number.isEmpty()) {
            return ToolResult.failed("Ask the user for the tracking number. It starts with SD.");
        }
        Optional<ParcelTracking> found = tracking.find(number, caller.clientKey());
        if (found.isEmpty()) {
            return ToolResult.of(Map.of(
                    "found", false,
                    "trackingNumber", number,
                    "message", "No parcel has this tracking number. The user should check the number."), null);
        }
        ParcelTracking parcel = found.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("trackingNumber", Words.trackingNumber(parcel.trackingNumber()));
        data.put("status", parcel.status().name());
        data.put("statusText", Words.status(parcel.status(), parcel.currentBranch(), lastFailure(parcel)));
        data.put("from", place(parcel.from()));
        data.put("to", place(parcel.to()));
        data.put("bookedAt", Words.when(parcel.createdAt()));
        data.put("lastUpdate", Words.when(parcel.updatedAt()));
        data.put("steps", parcel.steps().stream()
                .map(step -> Map.of(
                        "what", Words.status(step.status(), step.branch(), step.failureReason()),
                        "when", Words.when(step.occurredAt())))
                .toList());
        return ToolResult.of(data, new AssistantCard("TRACKING", MyParcelsTool.row(parcel)));
    }

    private static com.swiftdrop.delivery.FailureReason lastFailure(ParcelTracking parcel) {
        return parcel.steps().isEmpty() ? null : parcel.steps().getLast().failureReason();
    }

    static String place(ParcelTracking.Place place) {
        if (place == null) {
            return null;
        }
        String district = place.district() == null ? null : place.district().displayName();
        if (district == null || district.equalsIgnoreCase(place.city())) {
            return place.city();
        }
        return place.city() + ", " + district;
    }
}
