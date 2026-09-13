package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RefundConcurrencyIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;

  @ParameterizedTest
  @ValueSource(strings = {"CREDIT", "DEBIT"})
  void competingRefundsReturnOneInversePostingAndAnAlreadyRefundedReceipt(String operation)
      throws Exception {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    wallets.credit(player, 100, "test", "fund", "test", ref());
    var original =
        operation.equals("CREDIT")
            ? wallets.credit(player, 30, "test", "original", "test", ref())
            : wallets.debit(player, 30, "test", "original", "test", ref());
    UUID transaction = (UUID) original.get("transactionId");
    String tag = "refund-race-" + ref();
    var results = new ArrayList<CommandResult>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor();
        var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("select wallet_id from wallet where wallet_id=? for update")) {
        statement.setObject(1, player);
        statement.executeQuery().close();
      }
      try {
        var first = pool.submit(() -> refund(transaction, tag + "-a"));
        awaitBlocked(tag + "-a");
        var second = pool.submit(() -> refund(transaction, tag + "-b"));
        awaitBlocked(tag + "-b");
        // The holder prevents any refund from posting. Both contenders have reached a database
        // lock. Normally the second waits on the original's advisory lock. Without that guard,
        // both pass the already-refunded check and wait on the wallet.
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        holder.commit();
        assertThatCode(
                () -> {
                  results.add(first.get(10, TimeUnit.SECONDS));
                  results.add(second.get(10, TimeUnit.SECONDS));
                })
            .as("both refund commands return receipts instead of leaking a database exception")
            .doesNotThrowAnyException();
      } finally {
        holder.rollback();
      }
    }
    assertThat(results)
        .filteredOn(r -> r.status() == 200)
        .hasSize(1)
        .allSatisfy(
            r -> {
              assertThat(r.body().path("originalTransactionId").asText())
                  .isEqualTo(transaction.toString());
              assertThat(r.body().path("operation").asText()).isEqualTo("REFUND");
              assertThat(r.body().path("amount").asLong()).isEqualTo(30);
              assertThat(r.body().path("balanceAfter").asLong()).isEqualTo(100);
            });
    assertThat(results)
        .filteredOn(r -> r.status() == 409)
        .hasSize(1)
        .allSatisfy(r -> assertThat(r.body().path("code").asText()).isEqualTo("ALREADY_REFUNDED"));
    assertThat(wallets.balance(player))
        .containsEntry("balance", 100L)
        .containsEntry("sequence", 3L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from journal_transaction where original_transaction_id=?",
                Long.class,
                transaction))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where wallet_id=?", Long.class, player))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForList(
                """
        select e.account_id, sum(e.amount::numeric) total
        from ledger_entry e join journal_transaction j on j.transaction_id=e.transaction_id
        where j.transaction_id=? or j.original_transaction_id=?
        group by e.account_id having sum(e.amount::numeric) <> 0
        """,
                transaction,
                transaction))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                """
        select * from audit_ledger_integrity()
        where entity_id=? or entity_id in (
          select transaction_id from journal_transaction
          where transaction_id=? or original_transaction_id=?)
        """,
                player,
                transaction,
                transaction))
        .isEmpty();
  }

  private CommandResult refund(UUID transaction, String applicationName) {
    return commands.execute(
        "test",
        ref(),
        "refund",
        Map.of("id", transaction),
        () -> {
          jdbc.queryForObject(
              "select set_config('application_name', ?, true)", String.class, applicationName);
          return wallets.refund(transaction, "test", "cancel", "test", ref());
        });
  }

  private void awaitBlocked(String applicationName) {
    await()
        .atMost(Duration.ofSeconds(4))
        .pollInterval(Duration.ofMillis(10))
        .untilAsserted(
            () ->
                assertThat(
                        administratorJdbc()
                            .queryForObject(
                                "select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid)) > 0",
                                Long.class,
                                applicationName))
                    .isEqualTo(1));
  }

  private String ref() {
    return UUID.randomUUID().toString();
  }
}
