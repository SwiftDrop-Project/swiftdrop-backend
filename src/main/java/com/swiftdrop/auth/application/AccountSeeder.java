package com.swiftdrop.auth.application;

import com.swiftdrop.merchants.application.MerchantService;
import com.swiftdrop.merchants.application.MerchantViews.NewMerchant;
import com.swiftdrop.merchants.domain.ParcelsPerMonth;
import com.swiftdrop.shared.exception.ApiException;
import com.swiftdrop.shared.meta.District;
import com.swiftdrop.users.application.LoginAccount;
import com.swiftdrop.users.application.NewUser;
import com.swiftdrop.users.application.UserService;
import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates the seed accounts on startup. Safe to run on every start: an account whose email
 * already exists is left untouched. Logs never contain emails, phones or passwords.
 */
@Component
public class AccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    static final String ADMIN_PHONE = "+94770000000";
    static final String DEMO_CUSTOMER_EMAIL = "customer@swiftdrop.lk";
    static final String DEMO_MERCHANT_EMAIL = "merchant@swiftdrop.lk";
    static final String DEMO_COURIER_EMAIL = "courier@swiftdrop.lk";

    private final SeedProperties seed;
    private final UserService userService;
    private final MerchantService merchantService;

    public AccountSeeder(SeedProperties seed, UserService userService, MerchantService merchantService) {
        this.seed = seed;
        this.userService = userService;
        this.merchantService = merchantService;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID adminId = seedAdmin();
        if (seed.demoUsers()) {
            seedDemoUsers(adminId);
        }
    }

    private UUID seedAdmin() {
        if (isBlank(seed.adminEmail()) || isBlank(seed.adminPassword())) {
            log.warn("ADMIN_EMAIL or ADMIN_PASSWORD is not set; no admin account was seeded.");
            return null;
        }
        if (!userService.existsByEmail(seed.adminEmail())) {
            createUser("admin", new NewUser("SwiftDrop Admin", seed.adminEmail(), ADMIN_PHONE, seed.adminPassword(),
                    Role.ADMIN, UserStatus.ACTIVE));
        }
        return userService.findLoginAccountByEmail(seed.adminEmail()).map(LoginAccount::id).orElse(null);
    }

    private void seedDemoUsers(UUID adminId) {
        if (isBlank(seed.demoPassword())) {
            log.warn("SEED_DEMO_USERS is true but DEMO_PASSWORD is not set; demo accounts were not seeded.");
            return;
        }
        if (!userService.existsByEmail(DEMO_CUSTOMER_EMAIL)) {
            createUser("demo customer", new NewUser("Demo Customer", DEMO_CUSTOMER_EMAIL, "+94770000001",
                    seed.demoPassword(), Role.CUSTOMER, UserStatus.ACTIVE));
        }
        if (!userService.existsByEmail(DEMO_MERCHANT_EMAIL)) {
            try {
                var merchant = merchantService.registerMerchant(new NewMerchant("Demo Merchant", DEMO_MERCHANT_EMAIL,
                        "+94770000002", seed.demoPassword(), "SwiftDrop Demo Store", null,
                        ParcelsPerMonth.FROM_50_TO_200, District.COLOMBO, "Colombo"));
                merchantService.approve(merchant.user().id(), adminId);
                log.info("Seeded the demo merchant account.");
            } catch (ApiException ex) {
                log.warn("Could not seed the demo merchant account: {}", ex.code());
            }
        }
        if (!userService.existsByEmail(DEMO_COURIER_EMAIL)) {
            createUser("demo courier", new NewUser("Demo Courier", DEMO_COURIER_EMAIL, "+94770000003",
                    seed.demoPassword(), Role.COURIER, UserStatus.ACTIVE));
        }
    }

    private void createUser(String label, NewUser user) {
        try {
            userService.register(user);
            log.info("Seeded the {} account.", label);
        } catch (ApiException ex) {
            // e.g. the seed phone number is already used by someone else
            log.warn("Could not seed the {} account: {}", label, ex.code());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
