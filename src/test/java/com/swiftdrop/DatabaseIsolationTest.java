package com.swiftdrop;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the tests run against the Testcontainers PostgreSQL and never against the database in .env
 * (which may be the team's shared Neon database), and that each module's tables are in their own schema.
 */
class DatabaseIsolationTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    Environment environment;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appAndFlywayUseTheTestcontainersDatabase() throws SQLException {
        String containerHostAndPort = postgres.getHost() + ":" + postgres.getMappedPort(5432);

        assertThat(urlOf(dataSource)).contains(containerHostAndPort).doesNotContain("neon.tech")
                .isNotEqualTo(UNREACHABLE_DB_URL);
        assertThat(urlOf(flyway.getConfiguration().getDataSource())).contains(containerHostAndPort);
    }

    @Test
    void theEnvDatabaseIsNeverUsed() throws SQLException {
        // DB_URL is set when a developer has a .env (read through spring.config.import) or an environment variable.
        String envDbUrl = environment.getProperty("DB_URL");
        if (envDbUrl != null && !envDbUrl.isBlank()) {
            assertThat(urlOf(dataSource)).isNotEqualTo(envDbUrl);
        }
    }

    @Test
    void eachModuleOwnsItsSchemaAndThereAreNoCrossSchemaForeignKeys() {
        assertThat(schemaOf("users")).containsExactly("identity");
        assertThat(schemaOf("verification_codes")).containsExactly("identity");
        assertThat(schemaOf("merchant_profiles")).containsExactly("merchants");
        assertThat(schemaOf("email_log")).containsExactly("notifications");
        assertThat(schemaOf("courier_assignments")).containsExactly("dispatch");
        assertThat(schemaOf("assignment_items")).containsExactly("dispatch");
        assertThat(schemaOf("flyway_schema_history")).containsExactly("public");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where constraint_type = 'FOREIGN KEY'
                  and table_schema in ('identity', 'merchants', 'notifications', 'dispatch')""", Long.class))
                .isZero();
    }

    private List<String> schemaOf(String table) {
        return jdbc.queryForList("select table_schema from information_schema.tables where table_name = ?",
                String.class, table);
    }

    private static String urlOf(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}
