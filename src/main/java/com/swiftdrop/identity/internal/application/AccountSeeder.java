package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Creates the seed accounts on startup: the admin, and with SEED_DEMO_USERS the demo customer and courier (the demo
 * merchant is seeded by the merchants module, which owns merchant approval, right after this runner).
 *
 * <p>Safe to run on every start and on several backends at once (shared database): an account whose email already
 * exists is left untouched, and if another instance creates it at the same moment, the unique constraint stops the
 * second insert, which is logged and skipped. Logs never contain emails, phones or passwords.
 */
@Component
@Order(1)
public class AccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    static final String ADMIN_PHONE = "+94770000000";
    static final String DEMO_CUSTOMER_EMAIL = "customer@swiftdrop.lk";
    static final String DEMO_COURIER_EMAIL = "courier@swiftdrop.lk";

    private final SeedProperties seed;
    private final UserService userService;

    public AccountSeeder(SeedProperties seed, UserService userService) {
        this.seed = seed;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        if (seed.demoUsers()) {
            seedDemoUsers();
        }
    }

    private void seedAdmin() {
        if (isBlank(seed.adminEmail()) || isBlank(seed.adminPassword())) {
            log.warn("ADMIN_EMAIL or ADMIN_PASSWORD is not set; no admin account was seeded.");
            return;
        }
        if (!userService.existsByEmail(seed.adminEmail())) {
            createUser("admin", new NewUser("SwiftDrop Admin", seed.adminEmail(), ADMIN_PHONE, seed.adminPassword(),
                    Role.ADMIN, UserStatus.ACTIVE));
        }
    }

    private void seedDemoUsers() {
        if (isBlank(seed.demoPassword())) {
            log.warn("SEED_DEMO_USERS is true but DEMO_PASSWORD is not set; demo accounts were not seeded.");
            return;
        }
        if (!userService.existsByEmail(DEMO_CUSTOMER_EMAIL)) {
            createUser("demo customer", new NewUser("Demo Customer", DEMO_CUSTOMER_EMAIL, "+94770000001",
                    seed.demoPassword(), Role.CUSTOMER, UserStatus.ACTIVE));
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
            if (ex.code() == ErrorCode.EMAIL_ALREADY_REGISTERED) {
                // Another backend instance created it just after our existence check.
                log.info("The {} account already exists; skipped.", label);
            } else {
                // e.g. the seed phone number is already used by someone else
                log.warn("Could not seed the {} account: {}", label, ex.code());
            }
        } catch (DataIntegrityViolationException ex) {
            // Another backend instance created it between our check and our insert.
            log.info("The {} account was created by another instance at the same time; skipped.", label);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
