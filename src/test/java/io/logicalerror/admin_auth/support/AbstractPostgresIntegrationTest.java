package io.logicalerror.admin_auth.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class AbstractPostgresIntegrationTest {
    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("admin_auth_db")
                    .withUsername("postgres")
                    .withPassword("test_postgres_password")
                    .withInitScript("db/test-bootstrap.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "admin_auth_runtime");
        registry.add("spring.datasource.password", () -> "test_runtime_password");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "admin_auth_migration");
        registry.add("spring.flyway.password", () -> "test_migration_password");
    }
}
