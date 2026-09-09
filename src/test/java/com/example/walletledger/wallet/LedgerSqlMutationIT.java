package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.function.UnaryOperator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** PIT cannot mutate SQL. Each case checks a green control, then a live, isolated SQL mutant. */
@Testcontainers
class LedgerSqlMutationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql");

  @ParameterizedTest(name = "kills SQL mutant: {0}")
  @MethodSource("mutants")
  void acceptanceTestsKillExecutableSqlMutants(Mutant mutant) throws Exception {
    var flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), "wallet_migration", "wallet_migration_local")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "wallet_app", "wallet_app_local");
    var probe = new LedgerIntegrityIT();
    probe.jdbcUrl = POSTGRES.getJdbcUrl();
    probe.jdbc = new JdbcTemplate(source);
    var transaction = new TransactionInterceptor();
    transaction.setTransactionManager(new DataSourceTransactionManager(source));
    transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var factory =
        new ProxyFactory(
            new WalletService(
                JdbcClient.create(source),
                new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC()));
    factory.addAdvice(transaction);
    probe.wallets = (WalletService) factory.getProxy();

    // Setup/discovery failures are not mutant kills: the same acceptance example must pass first.
    mutant.acceptance.run(probe);
    var owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "wallet_migration", "wallet_migration_local"));
    String original =
        owner.queryForObject(
            "select pg_catalog.pg_get_functiondef(cast(? as pg_catalog.regprocedure))",
            String.class,
            "public." + mutant.function + "()");
    String changed = mutant.edit.apply(original);
    assertThat(changed).as("mutant must change its target").isNotEqualTo(original);
    owner.execute(changed); // Syntax/permission errors fail the harness, not count as kills.

    Throwable killed = catchThrowable(() -> mutant.acceptance.run(probe));
    assertThat(killed).as("survived SQL mutant %s", mutant.name).isNotNull();
    if (!(killed instanceof AssertionError)) {
      // A mutant rejecting valid money is also killed, but an arbitrary infrastructure error is
      // not.
      Throwable cause = killed;
      while (cause.getCause() != null) cause = cause.getCause();
      assertThat(cause).isInstanceOf(SQLException.class);
      assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
    }
    System.out.println("SQL mutant KILLED: " + mutant.name);
  }

  static List<Mutant> mutants() {
    return List.of(
        wallet(
            "omit required player metadata",
            replace("WHERE a.account_id = NEW.account_id AND a.kind = 'PLAYER'", "WHERE false"),
            LedgerIntegrityIT::playerAccountRequiresWalletMetadata),
        wallet(
            "omit entry ownership",
            replace("IF NEW.account_id <> current_wallet.account_id THEN", "IF false THEN"),
            LedgerIntegrityIT::entryAccountMustBelongToItsWallet),
        wallet(
            "omit predecessor existence",
            replace("IF NOT FOUND THEN", "IF false THEN"),
            LedgerIntegrityIT::missingPredecessorCannotCommitEvenWhenBalanceAndTailAgree),
        wallet(
            "nonzero first-entry base",
            replace("previous_balance := 0;", "previous_balance := 1;"),
            LedgerIntegrityIT::firstEntryMustStartAtZero),
        wallet(
            "omit intermediate arithmetic",
            replace(
                "IF previous_balance::NUMERIC + NEW.amount::NUMERIC <> NEW.balance_after::NUMERIC THEN",
                "IF false THEN"),
            LedgerIntegrityIT::badIntermediateBalanceCannotBeCompensatedByCorrectFinalAggregate),
        wallet(
            "overflow bigint before casting",
            replace(
                "previous_balance::NUMERIC + NEW.amount::NUMERIC",
                "(previous_balance + NEW.amount)::NUMERIC"),
            LedgerIntegrityIT::overflowingPredecessorAdditionIsAnIntegrityViolation),
        wallet(
            "omit final wallet tail",
            replace(
                "IF current_wallet.balance <> coalesce(tail_balance, 0)\n        OR current_wallet.sequence <> coalesce(tail_sequence, 0) THEN",
                "IF false THEN"),
            LedgerIntegrityIT::newPlayerEntryRequiresWalletUpdate),
        wallet(
            "validate intermediate NEW wallet image",
            replace(
                "IF TG_TABLE_NAME = 'ledger_entry' THEN",
                "IF TG_TABLE_NAME = 'wallet' THEN current_wallet := NEW; END IF;\n    IF TG_TABLE_NAME = 'ledger_entry' THEN"),
            LedgerIntegrityIT::severalValidPostingsUseFinalWalletStateAtDeferredCommit),
        wallet(
            "restore unsafe wallet name resolution",
            LedgerSqlMutationIT::unsafeNames,
            LedgerIntegrityIT::temporaryWalletAndLedgerCannotHidePermanentBalanceDrift),
        new Mutant(
            "omit journal header amount",
            "check_complete_journal",
            replace(
                "bool_and(abs(e.amount::NUMERIC) = header_amount AND e.currency = header_currency)",
                "bool_and(true)"),
            LedgerIntegrityIT::temporaryJournalCannotChangePermanentHeaderAmount),
        new Mutant(
            "restore unsafe journal name resolution",
            "check_complete_journal",
            LedgerSqlMutationIT::unsafeNames,
            LedgerIntegrityIT::temporaryLedgerCannotCompleteAPermanentJournal),
        new Mutant(
            "allow immutable history updates",
            "reject_history_mutation",
            replace(
                "RAISE EXCEPTION 'journal history is immutable' USING ERRCODE = '23514';",
                "RETURN OLD;"),
            LedgerIntegrityIT::runtimeAndMigrationOwnerCannotRewriteHistoryWithTemporaryShadows));
  }

  private static String unsafeNames(String sql) {
    return sql.replaceAll("(?m)^ SET search_path[^\\n]*\\n", "").replace("public.", "");
  }

  private static UnaryOperator<String> replace(String from, String to) {
    return sql -> {
      assertThat(sql).as("mutation location must exist").contains(from);
      return sql.replace(from, to);
    };
  }

  private static Mutant wallet(String name, UnaryOperator<String> edit, Acceptance acceptance) {
    return new Mutant(name, "check_wallet_ledger", edit, acceptance);
  }

  private record Mutant(
      String name, String function, UnaryOperator<String> edit, Acceptance acceptance) {
    @Override
    public String toString() {
      return name;
    }
  }

  @FunctionalInterface
  private interface Acceptance {
    void run(LedgerIntegrityIT probe) throws Exception;
  }
}
