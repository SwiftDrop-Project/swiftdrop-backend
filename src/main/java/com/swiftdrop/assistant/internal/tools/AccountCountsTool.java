package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserRoleCounts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Admins: how many accounts there are of each role ({@link IdentityApi#getUserRoleCounts}). Counts only, no names. */
@Component
public class AccountCountsTool implements AssistantTool {

    public static final String NAME = "account_counts";

    private final IdentityApi identity;

    AccountCountsTool(IdentityApi identity) {
        this.identity = identity;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Accounts (admins)";
    }

    @Override
    public String description() {
        return "How many customer, merchant, courier, dispatcher and admin accounts there are, and how many merchants "
                + "are waiting for approval. Admins only.";
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
        UserRoleCounts users = identity.getUserRoleCounts();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", users.totalUsers());
        data.put("customers", users.totalCustomers());
        data.put("merchants", users.totalMerchants());
        data.put("couriers", users.totalCouriers());
        data.put("dispatchers", users.totalDispatchers());
        data.put("admins", users.totalAdmins());
        data.put("pendingMerchants", users.pendingMerchants());
        AssistantCard card = AssistantCard.facts("Accounts", null, List.of(
                fact("Customers", users.totalCustomers()),
                fact("Merchants", users.totalMerchants()),
                fact("Merchants waiting for approval", users.pendingMerchants()),
                fact("Couriers", users.totalCouriers()),
                fact("Dispatchers", users.totalDispatchers()),
                fact("Admins", users.totalAdmins()),
                fact("All accounts", users.totalUsers())));
        return ToolResult.of(data, card);
    }

    private static AssistantCard.Fact fact(String label, long value) {
        return new AssistantCard.Fact(label, String.valueOf(value));
    }
}
