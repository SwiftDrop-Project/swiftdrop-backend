package com.swiftdrop.identity.internal.application;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Several backends sharing one database start at the same time: seeding must neither fail nor create duplicates. */
class AccountSeederConcurrencyTest extends IntegrationTest {

    private static final int INSTANCES = 4;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedersRunningAtTheSameTimeCreateEachAccountOnce() throws Exception {
        SeedProperties seed = new SeedProperties(ADMIN_EMAIL, ADMIN_PASSWORD, true, "Demo12345");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                AccountSeeder seeder = new AccountSeeder(seed, userService);
                runs.add(pool.submit(() -> {
                    start.await();
                    seeder.run(new DefaultApplicationArguments());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS); // throws if any seeder failed
            }
        } finally {
            pool.shutdownNow();
        }

        for (String email : List.of(ADMIN_EMAIL, AccountSeeder.DEMO_CUSTOMER_EMAIL, AccountSeeder.DEMO_COURIER_EMAIL)) {
            assertThat(jdbc.queryForObject("select count(*) from identity.users where email = ?", Long.class, email))
                    .as(email).isEqualTo(1L);
        }
    }
}
