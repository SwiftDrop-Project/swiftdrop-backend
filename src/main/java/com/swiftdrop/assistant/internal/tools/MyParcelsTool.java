package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.tracking.ParcelTracking;
import com.swiftdrop.tracking.TrackingApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The logged-in customer's bookings or merchant's orders, newest first. Never another user's. */
@Component
class MyParcelsTool implements AssistantTool {

    static final int LIMIT = 10;

    private final TrackingApi tracking;

    MyParcelsTool(TrackingApi tracking) {
        this.tracking = tracking;
    }

    @Override
    public String name() {
        return "my_parcels";
    }

    @Override
    public boolean availableTo(Caller caller) {
        return caller.ownsParcels();
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        if (!caller.ownsParcels()) {
            return ToolResult.failed("The user must log in as a customer or merchant to see their parcels.");
        }
        ParcelStatus status = null;
        String wanted = arguments.path("status").asString("").trim();
        if (!wanted.isEmpty()) {
            try {
                status = ParcelStatus.valueOf(wanted.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return ToolResult.failed("Unknown status " + wanted + ".");
            }
        }
        TrackingApi.Owner owner = "MERCHANT".equals(caller.role()) ? TrackingApi.Owner.MERCHANT
                : TrackingApi.Owner.CUSTOMER;
        List<ParcelTracking> parcels = tracking.mine(owner, caller.userId(), status, LIMIT);
        List<Map<String, Object>> rows = parcels.stream().map(MyParcelsTool::row).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        data.put("parcels", rows);
        return ToolResult.of(data, rows.isEmpty() ? null : new AssistantCard("PARCEL_LIST", Map.of("parcels", rows)));
    }

    /** One parcel for a card or a list: tracking number, status, towns and last update. */
    static Map<String, Object> row(ParcelTracking parcel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trackingNumber", parcel.trackingNumber());
        row.put("status", parcel.status().name());
        row.put("statusText", Words.status(parcel.status(), parcel.currentBranch(), null));
        row.put("from", TrackParcelTool.place(parcel.from()));
        row.put("to", TrackParcelTool.place(parcel.to()));
        row.put("currentBranch", parcel.currentBranch() == null ? null : parcel.currentBranch().name());
        row.put("lastUpdate", Words.when(parcel.updatedAt()));
        row.put("updatedAt", parcel.updatedAt() == null ? null : parcel.updatedAt().toString());
        return row;
    }
}
