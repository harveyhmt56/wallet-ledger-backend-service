package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Direct SQL acceptance tests: every adversarial transaction uses a fresh runtime-role session. */
class LedgerIntegrityIT extends PostgresIntegrationTest {
  private static final UUID ISSUANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PURCHASE = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Autowired WalletService wallets;
  @Autowired JdbcTemplate jdbc;
  String jdbcUrl;

  @Test
  void temporaryWalletAndLedgerCannotHidePermanentBalanceDrift() throws Exception {
    UUID player = provision(10);
    try (var connection = runtimeConnection()) {
      execute(connection, "create temporary table wallet as select * from public.wallet");
      execute(
          connection, "create temporary table ledger_entry as select * from public.ledger_entry");
      execute(connection, "update pg_temp.wallet set balance=110 where wallet_id=?", player);
      execute(
          connection,
          "update pg_temp.ledger_entry set amount=110,balance_after=110 where wallet_id=?",
          player);
      execute(connection, "update public.wallet set balance=110 where wallet_id=?", player);
      rejectsCommit(connection);
    }
    assertWallet(player, 10, 1);
  }

  @Test
  void temporaryLedgerCannotCompleteAPermanentJournal() throws Exception {
    UUID transaction = UUID.randomUUID();
    try (var connection = runtimeConnection()) {
      execute(
          connection,
          "create temporary table ledger_entry as select * from public.ledger_entry with no data");
      header(connection, transaction, 10);
      execute(
          connection,
          "insert into pg_temp.ledger_entry(entry_id,transaction_id,account_id,amount,currency) values (?,?,?,10,'COIN'),(?,?,?,-10,'COIN')",
          UUID.randomUUID(),
          transaction,
          ISSUANCE,
          UUID.randomUUID(),
          transaction,
          PURCHASE);
      rejectsCommit(connection);
    }
    assertNoJournal(transaction);
  }

  @Test
  void temporaryJournalCannotChangePermanentHeaderAmount() throws Exception {
    UUID transaction = UUID.randomUUID();
    try (var connection = runtimeConnection()) {
      execute(
          connection,
          "create temporary table journal_transaction as select * from public.journal_transaction with no data");
      header(connection, transaction, 11);
      execute(
          connection,
          "insert into pg_temp.journal_transaction select * from public.journal_transaction where transaction_id=?",
          transaction);
      execute(
          connection,
          "update pg_temp.journal_transaction set amount=10 where transaction_id=?",
          transaction);
      systemEntry(connection, transaction, ISSUANCE, 10);
      systemEntry(connection, transaction, PURCHASE, -10);
      rejectsCommit(connection);
    }
    assertNoJournal(transaction);
  }

  @Test
  void temporaryAccountCannotHideMissingPlayerMetadata() throws Exception {
    UUID player = provision(0);
    UUID transaction = UUID.randomUUID();
    try (var connection = runtimeConnection()) {
      execute(
          connection,
          "create temporary table ledger_account as select * from public.ledger_account");
      execute(
          connection,
          "update pg_temp.ledger_account set kind='ISSUANCE',player_id=null where account_id=?",
          account(player));
      header(connection, transaction, 10);
      systemEntry(connection, transaction, account(player), 10);
      systemEntry(connection, transaction, ISSUANCE, -10);
      rejectsCommit(connection);
    }
    assertWallet(player, 0, 0);
    assertNoJournal(transaction);
  }

  @Test
  void emptyWalletBalanceAndSequenceMustBothBeZero() throws Exception {
    UUID player = provision(0);
    for (String assignment : new String[] {"balance=1", "sequence=1"}) {
      try (var connection = runtimeConnection()) {
        execute(
            connection, "update public.wallet set " + assignment + " where wallet_id=?", player);
        rejectsCommit(connection);
      }
      assertWallet(player, 0, 0);
    }
  }

  @Test
  void existingWalletBalanceAndSequenceMustMatchFinalTail() throws Exception {
    UUID player = provision(10);
    for (String assignment : new String[] {"balance=11", "sequence=2", "sequence=0"}) {
      try (var connection = runtimeConnection()) {
        execute(
            connection, "update public.wallet set " + assignment + " where wallet_id=?", player);
        rejectsCommit(connection);
      }
      assertWallet(player, 10, 1);
    }
  }

  @Test
  void firstEntryMustStartAtZero() throws Exception {
    UUID player = provision(0);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 10, 1, 11);
      updateWallet(connection, player, 11, 1);
      rejectsCommit(connection);
    }
    assertWallet(player, 0, 0);
  }

  @Test
  void badIntermediateBalanceCannotBeCompensatedByCorrectFinalAggregate() throws Exception {
    UUID player = provision(10);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 10, 2, 21);
      updateWallet(connection, player, 21, 2);
      posting(connection, player, account(player), -5, 3, 15);
      updateWallet(connection, player, 15, 3);
      rejectsCommit(connection);
    }
    assertWallet(player, 10, 1);
  }

  @Test
  void missingPredecessorCannotCommitEvenWhenBalanceAndTailAgree() throws Exception {
    UUID player = provision(10);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 5, 3, 15);
      updateWallet(connection, player, 15, 3);
      rejectsCommit(connection);
    }
    assertWallet(player, 10, 1);
  }

  @Test
  void newPlayerEntryRequiresWalletUpdate() throws Exception {
    UUID player = provision(10);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 5, 2, 15);
      rejectsCommit(connection);
    }
    assertWallet(player, 10, 1);
  }

  @Test
  void playerAccountRequiresWalletMetadata() throws Exception {
    UUID player = provision(0);
    UUID transaction = UUID.randomUUID();
    try (var connection = runtimeConnection()) {
      header(connection, transaction, 10);
      systemEntry(connection, transaction, account(player), 10);
      systemEntry(connection, transaction, ISSUANCE, -10);
      rejectsCommit(connection);
    }
    assertWallet(player, 0, 0);
    assertNoJournal(transaction);
  }

  @Test
  void entryAccountMustBelongToItsWallet() throws Exception {
    UUID player = provision(0);
    UUID other = provision(0);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(other), 10, 1, 10);
      updateWallet(connection, player, 10, 1);
      rejectsCommit(connection);
    }
    assertWallet(player, 0, 0);
    assertWallet(other, 0, 0);
  }

  @Test
  void walletMustOwnItsPlayerAccount() throws Exception {
    UUID player = UUID.randomUUID();
    try (var connection = runtimeConnection()) {
      UUID account = UUID.randomUUID();
      execute(connection, "insert into public.player(player_id) values (?)", player);
      execute(
          connection,
          "insert into public.ledger_account(account_id,player_id,kind) values (?,?,'PLAYER')",
          account,
          player);
      execute(
          connection,
          "insert into public.wallet(wallet_id,player_id,account_id) values (?,?,?)",
          player,
          player,
          ISSUANCE);
      rejectsCommit(connection);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.wallet where wallet_id=?", Long.class, player))
        .isZero();
  }

  @Test
  void severalValidPostingsUseFinalWalletStateAtDeferredCommit() throws Exception {
    UUID player = provision(10);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 20, 2, 30);
      updateWallet(connection, player, 30, 2);
      posting(connection, player, account(player), -5, 3, 25);
      updateWallet(connection, player, 25, 3);
      posting(connection, player, account(player), 8, 4, 33);
      updateWallet(connection, player, 33, 4);
      connection.commit();
    }
    assertWallet(player, 33, 4);
  }

  @Test
  void numericArithmeticAllowsLegalBigintBoundaryWithoutIntermediateOverflow() throws Exception {
    UUID player = provision(0);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), Long.MAX_VALUE, 1, Long.MAX_VALUE);
      updateWallet(connection, player, Long.MAX_VALUE, 1);
      posting(connection, player, account(player), -1, 2, Long.MAX_VALUE - 1);
      updateWallet(connection, player, Long.MAX_VALUE - 1, 2);
      posting(connection, player, account(player), 1, 3, Long.MAX_VALUE);
      updateWallet(connection, player, Long.MAX_VALUE, 3);
      connection.commit();
    }
    assertWallet(player, Long.MAX_VALUE, 3);
  }

  @Test
  void overflowingPredecessorAdditionIsAnIntegrityViolation() throws Exception {
    UUID player = provision(Long.MAX_VALUE);
    try (var connection = runtimeConnection()) {
      posting(connection, player, account(player), 1, 2, 0);
      updateWallet(connection, player, 0, 2);
      rejectsCommit(connection);
    }
    assertWallet(player, Long.MAX_VALUE, 1);
  }

  @Test
  void incompleteAndUnbalancedPermanentJournalsCannotCommit() throws Exception {
    for (int entries : new int[] {0, 1, 2}) {
      UUID transaction = UUID.randomUUID();
      try (var connection = runtimeConnection()) {
        header(connection, transaction, 10);
        if (entries > 0) systemEntry(connection, transaction, ISSUANCE, 10);
        if (entries > 1) systemEntry(connection, transaction, PURCHASE, -9);
        rejectsCommit(connection);
      }
      assertNoJournal(transaction);
    }
  }

  @Test
  void runtimeCannotReplaceIntegrityFunctionsOrChangeWalletOwnership() throws Exception {
    UUID player = provision(0);
    for (String sql :
        new String[] {
          "create table public.untrusted_integrity_fixture(id integer)",
          "alter function public.check_complete_journal() rename to untrusted_integrity_fixture",
          "update public.wallet set account_id='" + ISSUANCE + "' where wallet_id='" + player + "'"
        }) {
      try (var connection = runtimeConnection()) {
        assertThatThrownBy(() -> execute(connection, sql))
            .isInstanceOfSatisfying(
                SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
        connection.rollback();
      }
    }
    assertWallet(player, 0, 0);
  }

  @Test
  void runtimeAndMigrationOwnerCannotRewriteHistoryWithTemporaryShadows() throws Exception {
    UUID player = provision(10);
    for (String role : new String[] {"wallet_app", "wallet_migration"}) {
      for (String sql :
          new String[] {
            "update public.ledger_entry set amount=9 where wallet_id='" + player + "'",
            "delete from public.ledger_entry where wallet_id='" + player + "'",
            "update public.journal_transaction set reason='rewritten' where transaction_id in (select transaction_id from public.ledger_entry where wallet_id='"
                + player
                + "')",
            "truncate public.ledger_entry"
          }) {
        try (var connection = roleConnection(role)) {
          execute(
              connection,
              "create temporary table ledger_entry as select * from public.ledger_entry with no data");
          execute(
              connection,
              "create temporary table journal_transaction as select * from public.journal_transaction with no data");
          assertThatThrownBy(() -> execute(connection, sql))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  failure -> assertThat(failure.getSQLState()).isIn("42501", "23514"));
          connection.rollback();
        }
      }
    }
    assertWallet(player, 10, 1);
  }

  private UUID provision(long balance) {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    if (balance > 0)
      wallets.credit(
          player,
          balance,
          "integrity-test",
          "fixture",
          "integrity-test",
          UUID.randomUUID().toString());
    return player;
  }

  private UUID account(UUID player) {
    return jdbc.queryForObject(
        "select account_id from public.wallet where wallet_id=?", UUID.class, player);
  }

  private void assertWallet(UUID player, long balance, long sequence) {
    assertThat(wallets.balance(player))
        .containsEntry("balance", balance)
        .containsEntry("sequence", sequence);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.ledger_entry where wallet_id=?", Long.class, player))
        .isEqualTo(sequence);
  }

  private void assertNoJournal(UUID transaction) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.journal_transaction where transaction_id=?",
                Long.class,
                transaction))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.ledger_entry where transaction_id=?",
                Long.class,
                transaction))
        .isZero();
  }

  private Connection runtimeConnection() throws SQLException {
    return roleConnection("wallet_app");
  }

  private Connection roleConnection(String role) throws SQLException {
    var connection =
        DriverManager.getConnection(
            jdbcUrl == null ? POSTGRES.getJdbcUrl() : jdbcUrl, role, role + "_local");
    connection.setAutoCommit(false);
    return connection;
  }

  private static void rejectsCommit(Connection connection) throws SQLException {
    assertThatThrownBy(connection::commit)
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
    connection.rollback();
  }

  private static void posting(
      Connection connection, UUID player, UUID account, long amount, long sequence, long balance)
      throws SQLException {
    UUID transaction = UUID.randomUUID();
    header(connection, transaction, Math.abs(amount));
    execute(
        connection,
        "insert into public.ledger_entry(entry_id,transaction_id,account_id,amount,wallet_id,wallet_sequence,balance_after) values (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        transaction,
        account,
        amount,
        player,
        sequence,
        balance);
    systemEntry(connection, transaction, ISSUANCE, -amount);
  }

  private static void header(Connection connection, UUID transaction, long amount)
      throws SQLException {
    execute(
        connection,
        "insert into public.journal_transaction(transaction_id,operation,amount,actor,reason,source,reference,created_at) values (?,'CREDIT',?,'integrity-test','integrity-test','integrity-test',?,clock_timestamp())",
        transaction,
        amount,
        transaction.toString());
  }

  private static void systemEntry(
      Connection connection, UUID transaction, UUID account, long amount) throws SQLException {
    execute(
        connection,
        "insert into public.ledger_entry(entry_id,transaction_id,account_id,amount) values (?,?,?,?)",
        UUID.randomUUID(),
        transaction,
        account,
        amount);
  }

  private static void updateWallet(Connection connection, UUID player, long balance, long sequence)
      throws SQLException {
    execute(
        connection,
        "update public.wallet set balance=?,sequence=? where wallet_id=?",
        balance,
        sequence,
        player);
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      statement.execute();
    }
  }
}
