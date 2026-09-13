package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DatabaseSafeguardsIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void runtimeAndOwnerCannotRewriteHistoryAndDirectBalanceDriftIsRejected() {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    var receipt = wallets.credit(player, 10, "test", "audit", "test", UUID.randomUUID().toString());
    UUID transaction = (UUID) receipt.get("transactionId");
    for (var connection : new JdbcTemplate[] {jdbc, migrationJdbc()}) {
      assertThatThrownBy(
              () ->
                  connection.update(
                      "update journal_transaction set reason='changed' where transaction_id=?",
                      transaction))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(
              () ->
                  connection.update("delete from ledger_entry where transaction_id=?", transaction))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(() -> connection.execute("truncate ledger_entry"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    assertThatThrownBy(() -> jdbc.update("update wallet set balance=11 where wallet_id=?", player))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(wallets.balance(player)).containsEntry("balance", 10L);
  }

  @Test
  void incompleteAndUnbalancedJournalCannotCommit() {
    var owner = migrationJdbc();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
    for (boolean entries : new boolean[] {false, true}) {
      UUID id = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        owner.update(
                            "insert into journal_transaction(transaction_id,operation,amount,actor,reason,source,reference,created_at) values (?,'CREDIT',10,'test','invalid','test',?,now())",
                            id,
                            id.toString());
                        if (entries) {
                          owner.update(
                              "insert into ledger_entry(entry_id,transaction_id,account_id,amount) values (?,?,'00000000-0000-0000-0000-000000000001',10),(?,?,'00000000-0000-0000-0000-000000000002',-9)",
                              UUID.randomUUID(),
                              id,
                              UUID.randomUUID(),
                              id);
                        }
                      }))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from journal_transaction where transaction_id=?",
                  Long.class,
                  id))
          .isZero();
    }
  }

  @Test
  void failureAtOutboxInsertionRollsBackMoneyJournalAndIdempotency() {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    String key = UUID.randomUUID().toString();
    var owner = migrationJdbc();
    owner.execute(
        "create function fail_test_outbox() returns trigger language plpgsql as $$ begin if NEW.wallet_id='"
            + player
            + "'::uuid then raise exception 'injected outbox failure'; end if; return NEW; end $$");
    owner.execute(
        "create trigger fail_test_outbox before insert on outbox_event for each row execute function fail_test_outbox()");
    try {
      assertThatThrownBy(
              () ->
                  commands.execute(
                      "test",
                      key,
                      "credit",
                      Map.of(),
                      () -> wallets.credit(player, 10, "test", "rollback", "test", key)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(wallets.balance(player))
          .containsEntry("balance", 0L)
          .containsEntry("sequence", 0L);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from journal_transaction where reference=?", Long.class, key))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from ledger_entry where wallet_id=?", Long.class, player))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from outbox_event where wallet_id=?", Long.class, player))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from idempotency_request where request_key=?", Long.class, key))
          .isZero();
    } finally {
      owner.execute("drop trigger fail_test_outbox on outbox_event");
      owner.execute("drop function fail_test_outbox()");
    }
    assertThat(
            commands
                .execute(
                    "test",
                    key,
                    "credit",
                    Map.of(),
                    () -> wallets.credit(player, 10, "test", "rollback", "test", key))
                .status())
        .isEqualTo(200);
  }

  @Test
  void competingDebitWaitsForWalletLockThenUsesCommittedBalance() throws Exception {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    wallets.credit(player, 100, "test", "fund", "test", UUID.randomUUID().toString());
    try (var holder = jdbc.getDataSource().getConnection();
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      holder.setAutoCommit(false);
      try (var lock =
          holder.prepareStatement("select balance from wallet where wallet_id=? for update")) {
        lock.setObject(1, player);
        lock.executeQuery().close();
      }
      var started = new CountDownLatch(1);
      var competing =
          pool.submit(
              () -> {
                started.countDown();
                return wallets.debit(
                    player, 80, "test", "buy", "test", UUID.randomUUID().toString());
              });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .until(
              () ->
                  administratorJdbc()
                          .queryForObject(
                              "select count(*) from pg_stat_activity where datname='wallet_ledger' and wait_event_type='Lock' and query like '%for update of w%'",
                              Long.class)
                      > 0);
      assertThat(competing).isNotDone();
      holder.commit();
      assertThat(competing.get(10, TimeUnit.SECONDS)).containsEntry("balanceAfter", 20L);
    }
  }

  @Test
  void competingDebitRejectsAfterWaitingForAnEarlierDebitToCommit() throws Exception {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    wallets.credit(player, 100, "test", "fund", "test", UUID.randomUUID().toString());
    String firstReference = UUID.randomUUID().toString();
    String secondReference = UUID.randomUUID().toString();
    String applicationName = "latest-balance-" + player;
    var posted = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var commitFirst = new AtomicBoolean(false);
    var transaction = new TransactionTemplate(transactionManager);
    var pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var first =
          pool.submit(
              () ->
                  transaction.execute(
                      status -> {
                        var receipt =
                            wallets.debit(
                                player, 80, "test", "first purchase", "test", firstReference);
                        posted.complete(
                            jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Earlier debit was not released");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(
                              "Earlier debit was interrupted", interrupted);
                        }
                        if (!commitFirst.get()) status.setRollbackOnly();
                        return receipt;
                      }));
      Future<CommandResult> competing = null;
      try {
        int holderPid = posted.get(5, TimeUnit.SECONDS);
        competing =
            pool.submit(
                () ->
                    commands.execute(
                        "test",
                        secondReference,
                        "debit:" + player,
                        Map.of("amount", 80),
                        () -> {
                          jdbc.queryForObject(
                              "select set_config('application_name', ?, true)",
                              String.class,
                              applicationName);
                          return wallets.debit(
                              player, 80, "test", "second purchase", "test", secondReference);
                        }));
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(4))
            .pollInterval(java.time.Duration.ofMillis(10))
            .untilAsserted(
                () ->
                    assertThat(
                            administratorJdbc()
                                .queryForObject(
                                    "select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock' and ? = any(pg_blocking_pids(pid))",
                                    Long.class,
                                    applicationName,
                                    holderPid))
                        .isEqualTo(1));
        assertThat(competing).isNotDone();
        commitFirst.set(true);
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).containsEntry("balanceAfter", 20L);
        var rejected = competing.get(10, TimeUnit.SECONDS);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body().path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
      } finally {
        release.countDown();
        if (!first.isDone()) first.cancel(true);
        if (competing != null && !competing.isDone()) competing.cancel(true);
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).as("debit workers stopped").isTrue();
    }
    assertThat(wallets.balance(player)).containsEntry("balance", 20L).containsEntry("sequence", 2L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from journal_transaction j join ledger_entry e on e.transaction_id=j.transaction_id where e.wallet_id=? and j.operation='DEBIT'",
                Long.class,
                player))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from journal_transaction where reference=?",
                Long.class,
                secondReference))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entry where wallet_id=?", Long.class, player))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where wallet_id=?", Long.class, player))
        .isEqualTo(2);
  }
}
