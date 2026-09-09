package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LongHistoryIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate jdbc;

  @Test
  void longHistoriesSupportPostingRefundAndConcurrentRequestsWithinGenerousDeadline()
      throws Exception {
    UUID sender = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    wallets.provision(sender);
    wallets.provision(recipient);
    HistoryFixture.populate(administratorJdbc(), sender, 20_000);
    HistoryFixture.populate(administratorJdbc(), recipient, 20_000);
    // Includes the real JDBC COMMIT and its deferred checks; this is a regression bound,
    // not a production latency promise. Fixture construction and audit are outside it.
    long began = System.nanoTime();
    var credit =
        wallets.credit(sender, 10, "history", "credit", "history", UUID.randomUUID().toString());
    assertThat(Duration.ofNanos(System.nanoTime() - began))
        .as("one credit including deferred COMMIT at 20k entries")
        .isLessThan(Duration.ofSeconds(10));
    wallets.debit(sender, 5, "history", "debit", "history", UUID.randomUUID().toString());
    wallets.transfer(
        sender, recipient, 3, "history", "transfer", "history", UUID.randomUUID().toString());
    wallets.refund(
        (UUID) credit.get("transactionId"),
        "history",
        "refund",
        "history",
        UUID.randomUUID().toString());

    var refundable = new ArrayList<UUID>();
    for (int i = 0; i < 4; i++) {
      refundable.add(
          (UUID)
              wallets
                  .credit(
                      sender,
                      1,
                      "history",
                      "refund race fixture",
                      "history",
                      UUID.randomUUID().toString())
                  .get("transactionId"));
    }
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 16; i++) {
        int operation = i % 4;
        UUID original = refundable.get(i / 4);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  String reference = UUID.randomUUID().toString();
                  return switch (operation) {
                    case 0 -> wallets.credit(sender, 1, "history", "race", "history", reference);
                    case 1 -> wallets.debit(sender, 1, "history", "race", "history", reference);
                    case 2 ->
                        wallets.transfer(
                            sender, recipient, 1, "history", "race", "history", reference);
                    default -> wallets.refund(original, "history", "race", "history", reference);
                  };
                }));
      }
      start.countDown();
      for (var future : futures) future.get(30, TimeUnit.SECONDS);
    }
    assertThat(wallets.balance(sender))
        .containsEntry("balance", 19_988L)
        .containsEntry("sequence", 20_024L);
    assertThat(wallets.balance(recipient))
        .containsEntry("balance", 20_007L)
        .containsEntry("sequence", 20_005L);
    HistoryFixture.assertConsistent(jdbc, sender);
    HistoryFixture.assertConsistent(jdbc, recipient);
  }
}

/** Bulk preparation only: this helper never disables triggers on a production/Compose database. */
final class HistoryFixture {
  private HistoryFixture() {}

  static void populate(JdbcTemplate administrator, UUID wallet, int count) throws Exception {
    try (Connection connection = administrator.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL session_replication_role = replica");
        statement.execute(
            "CREATE TEMP TABLE history_fixture(sequence bigint, transaction_id uuid) ON COMMIT DROP");
      }
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO history_fixture SELECT n, gen_random_uuid() FROM generate_series(1, ?) n")) {
        statement.setInt(1, count);
        statement.executeUpdate();
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(
            """
            INSERT INTO public.journal_transaction(transaction_id,operation,amount,actor,reason,source,reference,created_at)
            SELECT transaction_id,'CREDIT',1,'fixture','long history','history-fixture',transaction_id::text,clock_timestamp()
            FROM history_fixture
            """);
      }
      try (var statement =
          connection.prepareStatement(
              """
          INSERT INTO public.ledger_entry(entry_id,transaction_id,account_id,amount,wallet_id,wallet_sequence,balance_after)
          SELECT gen_random_uuid(),f.transaction_id,w.account_id,1,w.wallet_id,f.sequence,f.sequence
          FROM history_fixture f CROSS JOIN public.wallet w WHERE w.wallet_id=?
          UNION ALL
          SELECT gen_random_uuid(),transaction_id,'00000000-0000-0000-0000-000000000001'::uuid,-1,null,null,null
          FROM history_fixture
          """)) {
        statement.setObject(1, wallet);
        statement.executeUpdate();
      }
      try (var statement =
          connection.prepareStatement(
              "UPDATE public.wallet SET balance=?, sequence=? WHERE wallet_id=?")) {
        statement.setInt(1, count);
        statement.setInt(2, count);
        statement.setObject(3, wallet);
        statement.executeUpdate();
      }
      try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL session_replication_role = origin");
      }
      connection.commit();
    }
    assertConsistent(administrator, wallet);
  }

  static void assertConsistent(JdbcTemplate jdbc, UUID wallet) {
    assertThat(
            jdbc.queryForObject(
                """
        WITH history AS (
          SELECT e.*, sum(e.amount::numeric) OVER (ORDER BY e.wallet_sequence) expected_balance,
                 row_number() OVER (ORDER BY e.wallet_sequence) expected_sequence
          FROM public.ledger_entry e WHERE e.wallet_id=?
        )
        SELECT count(*) FROM history h JOIN public.wallet w USING(wallet_id)
        JOIN public.ledger_account a ON a.account_id=w.account_id
        WHERE h.balance_after::numeric<>h.expected_balance OR h.wallet_sequence<>h.expected_sequence
           OR h.account_id<>w.account_id OR a.player_id<>w.player_id OR a.kind<>'PLAYER'
        """,
                Long.class,
                wallet))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
        SELECT count(*) FROM public.wallet w WHERE wallet_id=? AND
        (w.balance::numeric<>(SELECT coalesce(sum(amount::numeric),0) FROM public.ledger_entry WHERE wallet_id=w.wallet_id)
         OR w.sequence<>(SELECT count(*) FROM public.ledger_entry WHERE wallet_id=w.wallet_id))
        """,
                Long.class,
                wallet))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
        SELECT count(*) FROM (
          SELECT j.transaction_id FROM public.journal_transaction j
          JOIN public.ledger_entry e ON e.transaction_id=j.transaction_id
          WHERE EXISTS (SELECT 1 FROM public.ledger_entry own WHERE own.transaction_id=j.transaction_id AND own.wallet_id=?)
          GROUP BY j.transaction_id,j.amount,j.currency
          HAVING count(*)<>2 OR count(DISTINCT e.account_id)<>2 OR sum(e.amount::numeric)<>0
             OR NOT bool_and(abs(e.amount::numeric)=j.amount AND e.currency=j.currency)
        ) invalid
        """,
                Long.class,
                wallet))
        .isZero();
  }
}
