package com.example.walletledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationStartupIT extends PostgresIntegrationTest {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void startsWithMigratedDatabaseAndRestrictedRuntimeRole() {
    assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("wallet_app");
    assertThat(jdbc.queryForObject("select count(*) from wallet", Long.class)).isNotNegative();
    assertThat(
            migrationJdbc()
                .queryForObject(
                    "select count(*) from flyway_schema_history where success = true", Long.class))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
        .isFalse();
  }
}
