package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** The demo merchant from API.md "Seed accounts": approved, can log in, seeded once even by parallel backends. */
class DemoMerchantSeederTest extends IntegrationTest {

    private static final String DEMO_PASSWORD = "Demo12345";

    @Autowired
    IdentityApi identity;

    @Autowired
    MerchantService merchantService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedsOneApprovedDemoMerchantThatCanLogIn() throws Exception {
        DemoSeedProperties seed = new DemoSeedProperties(ADMIN_EMAIL, true, DEMO_PASSWORD);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                DemoMerchantSeeder seeder = new DemoMerchantSeeder(seed, identity, merchantService);
                runs.add(pool.submit(() -> {
                    start.await();
                    seeder.run(new DefaultApplicationArguments());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String email = DemoMerchantSeeder.DEMO_MERCHANT_EMAIL;
        assertThat(jdbc.queryForObject("select count(*) from identity.users where email = ?", Long.class, email))
                .isEqualTo(1L);
        UUID merchantId = identity.findByEmail(email).map(UserSummary::id).orElseThrow();
        UUID adminId = identity.findByEmail(ADMIN_EMAIL).map(UserSummary::id).orElseThrow();
        assertThat(jdbc.queryForObject("select reviewed_by from merchants.merchant_profiles where user_id = ?",
                UUID.class, merchantId)).isEqualTo(adminId);

        login("0770000002", DEMO_PASSWORD, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant.businessName").value("SwiftDrop Demo Store"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));
    }
}
