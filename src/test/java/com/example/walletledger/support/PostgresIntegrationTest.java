package com.example.walletledger.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** One disposable real database per test JVM; do not enable container reuse. */
@SpringBootTest
@ActiveProfiles("local")
public abstract class PostgresIntegrationTest {
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "wallet_app");
    registry.add("spring.datasource.password", () -> "wallet_app_local");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "wallet_migration");
    registry.add("spring.flyway.password", () -> "wallet_migration_local");
    registry.add("ledger.outbox.enabled", () -> false);
    registry.add("ledger.consumer.enabled", () -> false);
    registry.add("ledger.rate-limit.enabled", () -> false);
    registry.add("spring.kafka.admin.auto-create", () -> false);
    registry.add("spring.kafka.listener.auto-startup", () -> false);
    registry.add("management.health.redis.enabled", () -> false);
  }

  protected static JdbcTemplate migrationJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "wallet_migration", "wallet_migration_local"));
  }

  protected static JdbcTemplate administratorJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }
}
