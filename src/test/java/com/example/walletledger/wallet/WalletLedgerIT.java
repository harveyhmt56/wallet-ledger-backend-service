package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WalletLedgerIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;

  @Test
  void provisionsCreditsAndDebitsWithImmutableBalancedJournalAndEvents() {
    UUID player = UUID.randomUUID();
    assertThat(wallets.provision(player)).containsEntry("balance", 0L);
    Map<String, Object> credit = credit(player, 100);
    assertThat(credit).containsEntry("balanceAfter", 100L);
    Map<String, Object> debit = wallets.debit(player, 100, "test", "purchase", "test", ref());
    assertThat(debit).containsEntry("balanceAfter", 0L);
    assertThat(wallets.balance(player)).containsEntry("balance", 0L).containsEntry("sequence", 2L);
    UUID transaction = (UUID) debit.get("transactionId");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entry where transaction_id=?",
                Long.class,
                transaction))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select sum(amount) from ledger_entry where transaction_id=?",
                Long.class,
                transaction))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select reason from journal_transaction where transaction_id=?",
                String.class,
                transaction))
        .isEqualTo("purchase");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where wallet_id=?", Long.class, player))
        .isEqualTo(2);
    assertThat(wallets.reconciliation()).containsEntry("consistent", true);
  }

  @Test
  void rejectsInsufficientFundsAndRecipientOverflowWithoutPartialMovement() {
    UUID player = player();
    assertThatThrownBy(() -> wallets.debit(player, 1, "test", "buy", "test", ref()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));
    UUID recipient = player();
    credit(player, 10);
    credit(recipient, Long.MAX_VALUE);
    assertThatThrownBy(() -> wallets.transfer(player, recipient, 1, "test", "gift", "test", ref()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("BALANCE_LIMIT"));
    assertThat(wallets.balance(player)).containsEntry("balance", 10L);
    assertThat(wallets.balance(recipient)).containsEntry("balance", Long.MAX_VALUE);
  }

  @Test
  void exactlyFiftyOfOneHundredConcurrentDebitsSucceed() throws Exception {
    UUID player = player();
    credit(player, 500);
    List<CommandResult> outcomes =
        concurrent(
            100,
            n ->
                command(
                    "debit",
                    Map.of("player", player, "amount", 10),
                    () -> wallets.debit(player, 10, "test", "concurrent", "test", ref())));
    assertThat(outcomes).filteredOn(r -> r.status() == 200).hasSize(50);
    assertThat(outcomes)
        .filteredOn(r -> r.status() == 409)
        .hasSize(50)
        .allSatisfy(
            r -> assertThat(r.body().path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS"));
    assertThat(wallets.balance(player)).containsEntry("balance", 0L).containsEntry("sequence", 51L);
  }

  @Test
  void hundredConcurrentCopiesReturnOnePersistedReceiptAndChangedPayloadConflicts()
      throws Exception {
    UUID player = player();
    String key = ref();
    String reference = ref();
    List<CommandResult> outcomes =
        concurrent(
            100,
            n ->
                commands.execute(
                    "test",
                    key,
                    "credit",
                    Map.of("player", player, "amount", 10),
                    () -> wallets.credit(player, 10, "test", "one reward", "test", reference)));
    assertThat(outcomes).allMatch(r -> r.status() == 200);
    assertThat(outcomes.stream().map(CommandResult::body).distinct()).hasSize(1);
    assertThat(wallets.balance(player)).containsEntry("balance", 10L).containsEntry("sequence", 1L);
    CommandResult changed =
        commands.execute(
            "test",
            key,
            "credit",
            Map.of("player", player, "amount", 11),
            () -> credit(player, 11));
    assertThat(changed.status()).isEqualTo(409);
    assertThat(changed.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    credit(player, 20);
    assertThat(
            commands
                .execute(
                    "test",
                    key,
                    "credit",
                    Map.of("amount", 10, "player", player),
                    () -> credit(player, 10))
                .body())
        .isEqualTo(outcomes.getFirst().body());
    assertThat(outcomes.getFirst().body().path("balanceAfter").asLong()).isEqualTo(10);
  }

  @Test
  void concurrentCreditsAndOpposingTransfersConserveBalances() throws Exception {
    UUID a = player();
    UUID b = player();
    concurrent(50, n -> credit(a, 10));
    credit(b, 500);
    List<Integer> outcomes =
        concurrent(
            60,
            n ->
                command(
                        "transfer",
                        Map.of("n", n),
                        () ->
                            wallets.transfer(
                                n % 2 == 0 ? a : b,
                                n % 2 == 0 ? b : a,
                                3,
                                "test",
                                "gift",
                                "test",
                                ref()))
                    .status());
    assertThat(outcomes).containsOnly(200);
    assertThat(wallets.balance(a)).containsEntry("balance", 500L);
    assertThat(wallets.balance(b)).containsEntry("balance", 500L);
  }

  @Test
  void onlyOneConcurrentRefundReversesOriginalAndFailedCreditReversalLeavesOriginal()
      throws Exception {
    UUID player = player();
    Map<String, Object> original = credit(player, 100);
    UUID transaction = (UUID) original.get("transactionId");
    List<CommandResult> outcomes =
        concurrent(
            20,
            n ->
                command(
                    "refund",
                    Map.of("id", transaction),
                    () -> wallets.refund(transaction, "admin", "cancel", "test", ref())));
    assertThat(outcomes).filteredOn(r -> r.status() == 200).hasSize(1);
    assertThat(outcomes)
        .filteredOn(r -> r.status() == 409)
        .hasSize(19)
        .allSatisfy(r -> assertThat(r.body().path("code").asText()).isEqualTo("ALREADY_REFUNDED"));
    assertThat(wallets.balance(player)).containsEntry("balance", 0L);
    assertThat(
            jdbc.queryForObject(
                "select amount from journal_transaction where transaction_id=?",
                Long.class,
                transaction))
        .isEqualTo(100);
    UUID creditId = (UUID) credit(player, 20).get("transactionId");
    wallets.debit(player, 20, "test", "purchase", "test", ref());
    assertThatThrownBy(() -> wallets.refund(creditId, "admin", "cancel", "test", ref()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));
    assertThat(wallets.balance(player)).containsEntry("balance", 0L);
  }

  @Test
  void businessRejectionReplaysAfterBalanceChangesAndReferenceCannotBeReused() {
    UUID player = player();
    String key = ref();
    Map<String, Object> payload = Map.of("player", player, "amount", 10);
    CommandResult failure =
        commands.execute(
            "test",
            key,
            "debit",
            payload,
            () -> wallets.debit(player, 10, "test", "buy", "test", ref()));
    assertThat(failure.status()).isEqualTo(409);
    credit(player, 20);
    assertThat(
            commands.execute(
                "test",
                key,
                "debit",
                payload,
                () -> wallets.debit(player, 10, "test", "buy", "test", ref())))
        .isEqualTo(failure);
    String reference = ref();
    wallets.credit(player, 1, "test", "grant", "test", reference);
    assertThatThrownBy(() -> wallets.credit(player, 1, "test", "grant", "test", reference))
        .isInstanceOf(BusinessException.class);
    assertThat(wallets.balance(player)).containsEntry("balance", 21L);
  }

  @Test
  void historyCursorRemainsStableDuringWrites() {
    UUID player = player();
    for (int i = 0; i < 5; i++) credit(player, 1);
    Map<String, Object> first = wallets.history(player, null, 2);
    assertThat((List<?>) first.get("items")).hasSize(2);
    long cursor = ((Number) first.get("nextCursor")).longValue();
    credit(player, 1);
    Map<String, Object> second = wallets.history(player, cursor, 2);
    List<Map<String, Object>> page = (List<Map<String, Object>>) second.get("items");
    assertThat(page)
        .extracting(row -> ((Number) row.get("walletSequence")).longValue())
        .containsExactly(3L, 2L);
  }

  private UUID player() {
    UUID id = UUID.randomUUID();
    wallets.provision(id);
    return id;
  }

  private Map<String, Object> credit(UUID id, long amount) {
    return wallets.credit(id, amount, "test", "grant", "test", ref());
  }

  private String ref() {
    return UUID.randomUUID().toString();
  }

  private CommandResult command(
      String operation, Object payload, java.util.function.Supplier<Map<String, Object>> action) {
    return commands.execute("test", ref(), operation, payload, action);
  }

  private <T> List<T> concurrent(int count, IntFunction<T> operation) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        final int n = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return operation.apply(n);
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
      return results;
    }
  }
}
