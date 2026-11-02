package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.MerchantProfiles.SignUp;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * With SEED_DEMO_USERS, creates the approved demo merchant {@code merchant@swiftdrop.lk} (mobile 0770000002).
 * Runs right after identity's AccountSeeder, so the admin who "approved" it already exists.
 *
 * <p>Safe on every start and on several backends at once: an existing account is left untouched, and if another
 * instance creates it at the same moment, the unique constraint rolls this attempt back, which is logged and skipped.
 */
@Component
@Order(2)
class DemoMerchantSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoMerchantSeeder.class);

    static final String DEMO_MERCHANT_EMAIL = "merchant@swiftdrop.lk";

    private final DemoSeedProperties seed;
    private final IdentityApi identity;
    private final MerchantService merchantService;

    DemoMerchantSeeder(DemoSeedProperties seed, IdentityApi identity, MerchantService merchantService) {
        this.seed = seed;
        this.identity = identity;
        this.merchantService = merchantService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed.demoUsers() || seed.demoPassword() == null || seed.demoPassword().isBlank()) {
            return;
        }
        if (identity.findByEmail(DEMO_MERCHANT_EMAIL).isPresent()) {
            return;
        }
        UUID adminId = seed.adminEmail() == null || seed.adminEmail().isBlank()
                ? null
                : identity.findByEmail(seed.adminEmail()).map(UserSummary::id).orElse(null);
        try {
            merchantService.seedApprovedMerchant("Demo Merchant", DEMO_MERCHANT_EMAIL, "+94770000002",
                    seed.demoPassword(), new SignUp("SwiftDrop Demo Store", null, ParcelsPerMonth.FROM_50_TO_200,
                            District.COLOMBO, "Colombo"), adminId);
            log.info("Seeded the demo merchant account.");
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.EMAIL_ALREADY_REGISTERED) {
                log.info("The demo merchant account already exists; skipped.");
            } else {
                log.warn("Could not seed the demo merchant account: {}", ex.code());
            }
        } catch (DataIntegrityViolationException ex) {
            log.info("The demo merchant account was created by another instance at the same time; skipped.");
        }
    }
}
