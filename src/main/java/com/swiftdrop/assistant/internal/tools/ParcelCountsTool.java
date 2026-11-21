package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.ParcelStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Admins: how many parcels are in each status right now ({@link DeliveryApi#countByStatus}). */
@Component
public class ParcelCountsTool implements AssistantTool {

    public static final String NAME = "parcel_counts";

    private final DeliveryApi deliveries;

    ParcelCountsTool(DeliveryApi deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Parcels by status now (admins)";
    }

    @Override
    public String description() {
        return "How many parcels are in each status right now (waiting for pickup, at a branch, out for delivery, "
                + "...), and in total. Admins only.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public boolean availableTo(Caller caller) {
        return caller.isAdmin();
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        Map<ParcelStatus, Long> counts = deliveries.countByStatus();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AssistantCard.Fact> facts = new ArrayList<>();
        long total = 0;
        for (ParcelStatus status : ParcelStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            total += count;
            rows.add(Map.of("status", status.name(), "label", Words.statusLabel(status), "count", count));
            facts.add(new AssistantCard.Fact(Words.statusLabel(status), String.valueOf(count)));
        }
        facts.add(new AssistantCard.Fact("All parcels", String.valueOf(total)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("counts", rows);
        data.put("total", total);
        return ToolResult.of(data, AssistantCard.facts("Parcels by status now", null, facts));
    }
}
