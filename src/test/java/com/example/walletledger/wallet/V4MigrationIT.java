package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Upgrade fixtures have their own database and never corrupt the shared application test schema.
 */
@Testcontainers
class V4MigrationIT {
  private static final UUID ISSUANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PURCHASE = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql");

  @BeforeEach
  void resetIsolatedSchema() {
    flyway(null).clean();
  }

  @Test
  void freshDatabaseInstallsV4AndHasNoAuditFindings() {
    flyway(null).migrate();

    assertThat(flyway(null).info().current().getVersion().getVersion()).isEqualTo("4");
    assertThat(runtime().queryForList("select * from public.audit_ledger_integrity()")).isEmpty();
  }

  @Test
  void populatedV3WithCreditDebitTransferRefundAndEmptyWalletUpgradesWithoutRewritingHistory() {
    flyway("3").migrate();
    seedValidHistory();
    var before = snapshot();

    flyway(null).migrate();

    assertThat(flyway(null).info().current().getVersion().getVersion()).isEqualTo("4");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(runtime().queryForList("select * from public.audit_ledger_integrity()")).isEmpty();
    assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
  }

  @ParameterizedTest(name = "populated V3 refuses {0}")
  @EnumSource(Corruption.class)
  void populatedV3CorruptionFailsPreflightWithoutChangingExistingData(Corruption corruption) {
    flyway("3").migrate();
    var fixture = seedValidHistory();
    corrupt(corruption, fixture);
    var before = snapshot();

    Throwable failure = catchThrowable(() -> flyway(null).migrate());

    assertThat(failure).as("V4 must refuse the %s fixture", corruption).isNotNull();
    assertThat(sqlFailure(failure).getSQLState()).isEqualTo("23514");
    assertThat(sqlFailure(failure).getMessage()).contains("ledger integrity preflight failed");
    assertThat(flyway("3").info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest(name = "operational audit reports {0}")
  @EnumSource(Corruption.class)
  void operationalAuditReportsCorruptionUsingRuntimeReadPrivileges(Corruption corruption) {
    flyway(null).migrate();
    var fixture = seedValidHistory();
    assertThat(runtime().queryForList("select * from public.audit_ledger_integrity()")).isEmpty();
    corrupt(corruption, fixture);

    var findings = runtime().queryForList("select * from public.audit_ledger_integrity()");

    assertThat(findings).as("audit must identify %s", corruption).isNotEmpty();
    assertThat(findings)
        .extracting(finding -> finding.get("issue"))
        .contains(corruption.expectedIssue);
    assertThat(findings)
        .allSatisfy(
            finding -> {
              assertThat(finding.get("issue")).isInstanceOf(String.class);
              assertThat((String) finding.get("issue")).isNotBlank();
              assertThat(finding.get("entity_id")).isInstanceOf(UUID.class);
            });
  }

  @Test
  void operationalAuditIgnoresRuntimeTemporaryTablesAndDoesNotElevatePrivileges() {
    flyway(null).migrate();
    var fixture = seedValidHistory();
    corrupt(Corruption.INTERMEDIATE_RUNNING_BALANCE, fixture);
    var jdbc = runtime();
    var transaction =
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

    transaction.executeWithoutResult(
        status -> {
          jdbc.execute("set local search_path = pg_temp, public, pg_catalog");
          for (String table :
              List.of(
                  "player", "ledger_account", "wallet", "journal_transaction", "ledger_entry")) {
            jdbc.execute("create temp table " + table + " (like public." + table + ")");
          }
          assertThat(jdbc.queryForList("select issue from public.audit_ledger_integrity()"))
              .extracting(finding -> finding.get("issue"))
              .contains("entry_balance");
          assertThat(
                  jdbc.queryForObject(
                      "select prosecdef from pg_catalog.pg_proc where oid='public.audit_ledger_integrity()'::pg_catalog.regprocedure",
                      Boolean.class))
              .isFalse();
        });
  }

  @Test
  void maintenanceScriptSucceedsForValidHistoryAndFailsWithoutRewritingCorruption()
      throws Exception {
    flyway(null).migrate();
    var fixture = seedValidHistory();
    String script = Files.readString(Path.of("scripts/audit-ledger.sql"));
    runtime().execute(script);
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(Path.of("scripts/audit-ledger.sql").toAbsolutePath()),
        "/tmp/audit-ledger.sql");
    assertThat(runAuditCli().getExitCode()).isZero();
    corrupt(Corruption.INTERMEDIATE_RUNNING_BALANCE, fixture);
    var before = snapshot();

    Throwable failure = catchThrowable(() -> runtime().execute(script));

    assertThat(failure).isNotNull();
    assertThat(sqlFailure(failure).getSQLState()).isEqualTo("23514");
    assertThat(sqlFailure(failure).getMessage()).contains("ledger audit found");
    var alert = runAuditCli();
    assertThat(alert.getExitCode()).isEqualTo(3);
    assertThat(alert.getStderr()).contains("ledger audit found");
    assertThat(snapshot()).isEqualTo(before);
  }

  private org.testcontainers.containers.Container.ExecResult runAuditCli() throws Exception {
    return POSTGRES.execInContainer(
        "psql",
        "-X",
        "-U",
        "wallet_app",
        "-d",
        "wallet_ledger",
        "--set=ON_ERROR_STOP=1",
        "--file=/tmp/audit-ledger.sql");
  }

  @Test
  void upgradeWaitsForInFlightWritesAndAuditsTheirCommittedState() throws Exception {
    flyway("3").migrate();
    var fixture = seedValidHistory();
    try (var writer = administrator().getDataSource().getConnection();
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      writer.setAutoCommit(false);
      try (var statement = writer.createStatement()) {
        statement.execute("set local session_replication_role=replica");
        statement.executeUpdate(
            "update public.wallet set balance=91 where wallet_id='" + fixture.alice + "'");
      }
      var migrating = pool.submit(() -> catchThrowable(() -> flyway(null).migrate()));
      try {
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    administrator()
                            .queryForObject(
                                """
                select count(*) from pg_catalog.pg_stat_activity
                where datname=current_database() and wait_event_type='Lock'
                    and query like '%LOCK TABLE public.player%'
                """,
                                Long.class)
                        > 0);
        assertThat(migrating).isNotDone();
      } finally {
        writer.commit();
      }
      Throwable failure = migrating.get(10, TimeUnit.SECONDS);
      assertThat(failure).isNotNull();
      assertThat(sqlFailure(failure).getSQLState()).isEqualTo("23514");
      assertThat(sqlFailure(failure).getMessage()).contains("ledger integrity preflight failed");
      assertThat(flyway("3").info().current().getVersion().getVersion()).isEqualTo("3");
    }
  }

  private Flyway flyway(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), "wallet_migration", "wallet_migration_local")
            .locations("classpath:db/migration")
            .cleanDisabled(false);
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }

  private JdbcTemplate owner() {
    return jdbc("wallet_migration", "wallet_migration_local");
  }

  private JdbcTemplate runtime() {
    return jdbc("wallet_app", "wallet_app_local");
  }

  private JdbcTemplate administrator() {
    return jdbc(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private JdbcTemplate jdbc(String username, String password) {
    return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), username, password));
  }

  private Fixture seedValidHistory() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID empty = UUID.randomUUID();
    UUID unprovisioned = UUID.randomUUID();
    UUID credit = UUID.randomUUID();
    UUID debit = UUID.randomUUID();
    UUID transfer = UUID.randomUUID();
    UUID refund = UUID.randomUUID();
    var jdbc = runtime();
    var transaction =
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          for (UUID player : List.of(alice, bob, empty)) {
            jdbc.update("insert into public.player(player_id) values (?)", player);
            jdbc.update(
                "insert into public.ledger_account(account_id,player_id,kind) values (?,?,'PLAYER')",
                player,
                player);
            jdbc.update(
                "insert into public.wallet(wallet_id,player_id,account_id) values (?,?,?)",
                player,
                player,
                player);
          }
          jdbc.update("insert into public.player(player_id) values (?)", unprovisioned);
          journal(jdbc, credit, "CREDIT", 100, null);
          entry(jdbc, credit, alice, 100, alice, 1L, 100L);
          entry(jdbc, credit, ISSUANCE, -100, null, null, null);
          UUID bobCredit = UUID.randomUUID();
          journal(jdbc, bobCredit, "CREDIT", 70, null);
          entry(jdbc, bobCredit, bob, 70, bob, 1L, 70L);
          entry(jdbc, bobCredit, ISSUANCE, -70, null, null, null);
          journal(jdbc, debit, "DEBIT", 20, null);
          entry(jdbc, debit, alice, -20, alice, 2L, 80L);
          entry(jdbc, debit, PURCHASE, 20, null, null, null);
          journal(jdbc, transfer, "TRANSFER", 10, null);
          entry(jdbc, transfer, alice, -10, alice, 3L, 70L);
          entry(jdbc, transfer, bob, 10, bob, 2L, 80L);
          journal(jdbc, refund, "REFUND", 20, debit);
          entry(jdbc, refund, alice, 20, alice, 4L, 90L);
          entry(jdbc, refund, PURCHASE, -20, null, null, null);
          jdbc.update("update public.wallet set balance=90, sequence=4 where wallet_id=?", alice);
          jdbc.update("update public.wallet set balance=80, sequence=2 where wallet_id=?", bob);
        });
    return new Fixture(alice, unprovisioned, credit, refund);
  }

  private void journal(JdbcTemplate jdbc, UUID id, String operation, long amount, UUID original) {
    jdbc.update(
        """
        insert into public.journal_transaction(transaction_id,operation,amount,actor,reason,source,
            reference,original_transaction_id,created_at)
        values (?,?,?,'upgrade-test','migration history','upgrade-test',?,?,now())
        """,
        id,
        operation,
        amount,
        id.toString(),
        original);
  }

  private void entry(
      JdbcTemplate jdbc,
      UUID journal,
      UUID account,
      long amount,
      UUID wallet,
      Long sequence,
      Long balance) {
    jdbc.update(
        """
        insert into public.ledger_entry(entry_id,transaction_id,account_id,amount,wallet_id,
            wallet_sequence,balance_after) values (?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        journal,
        account,
        amount,
        wallet,
        sequence,
        balance);
  }

  private void corrupt(Corruption corruption, Fixture fixture) {
    var admin = administrator();
    var transaction =
        new TransactionTemplate(new DataSourceTransactionManager(admin.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          // Model legacy inconsistent data; the runtime role cannot disable these safeguards.
          admin.execute("set local session_replication_role = replica");
          switch (corruption) {
            case INTERMEDIATE_RUNNING_BALANCE ->
                admin.update(
                    "update public.ledger_entry set balance_after=87 where wallet_id=? and wallet_sequence=2",
                    fixture.alice());
            case SEQUENCE_GAP ->
                admin.update(
                    "update public.ledger_entry set wallet_sequence=6 where wallet_id=? and wallet_sequence=2",
                    fixture.alice());
            case WALLET_OWNERSHIP ->
                admin.update(
                    "update public.wallet set player_id=? where wallet_id=?",
                    fixture.unprovisioned(),
                    fixture.alice());
            case ENTRY_ACCOUNT ->
                admin.update(
                    "update public.ledger_entry set account_id=? where wallet_id=? and wallet_sequence=1",
                    PURCHASE,
                    fixture.alice());
            case PLAYER_METADATA ->
                admin.update(
                    "update public.ledger_entry set wallet_id=null, wallet_sequence=null, balance_after=null where wallet_id=? and wallet_sequence=2",
                    fixture.alice());
            case INCOMPLETE_JOURNAL ->
                admin.update(
                    "delete from public.ledger_entry where transaction_id=? and account_id=?",
                    fixture.credit(),
                    ISSUANCE);
            case WALLET_TAIL ->
                admin.update(
                    "update public.wallet set balance=91 where wallet_id=?", fixture.alice());
            case REFUND_WRONG_ORIGINAL ->
                admin.update(
                    "update public.journal_transaction set original_transaction_id=? where transaction_id=?",
                    fixture.credit(),
                    fixture.refund());
            case REFUND_NOT_INVERSE -> {
              admin.update(
                  "update public.ledger_entry set amount=-amount where transaction_id=?",
                  fixture.refund());
              admin.update(
                  "update public.ledger_entry set balance_after=50 where wallet_id=? and wallet_sequence=4",
                  fixture.alice());
              admin.update(
                  "update public.wallet set balance=50 where wallet_id=?", fixture.alice());
            }
          }
        });
  }

  private List<String> snapshot() {
    var jdbc = owner();
    return List.of("player", "ledger_account", "wallet", "journal_transaction", "ledger_entry")
        .stream()
        .map(
            table ->
                jdbc.queryForObject(
                    "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]')::text from public."
                        + table
                        + " t",
                    String.class))
        .toList();
  }

  private SQLException sqlFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        return sql;
      }
    }
    throw new AssertionError("Expected a PostgreSQL failure", failure);
  }

  private enum Corruption {
    INTERMEDIATE_RUNNING_BALANCE("entry_balance"),
    SEQUENCE_GAP("entry_sequence"),
    WALLET_OWNERSHIP("wallet_ownership"),
    ENTRY_ACCOUNT("entry_account"),
    PLAYER_METADATA("entry_metadata"),
    INCOMPLETE_JOURNAL("journal_complete"),
    WALLET_TAIL("wallet_tail"),
    REFUND_WRONG_ORIGINAL("refund_inverse"),
    REFUND_NOT_INVERSE("refund_inverse");

    private final String expectedIssue;

    Corruption(String expectedIssue) {
      this.expectedIssue = expectedIssue;
    }
  }

  private record Fixture(UUID alice, UUID unprovisioned, UUID credit, UUID refund) {}
}
