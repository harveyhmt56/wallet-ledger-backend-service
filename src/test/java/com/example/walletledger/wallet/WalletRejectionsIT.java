package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WalletRejectionsIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate jdbc;

  enum MissingPlayerOperation {
    CREDIT,
    DEBIT,
    BALANCE,
    HISTORY,
    TRANSFER_SENDER,
    TRANSFER_RECIPIENT
  }

  @ParameterizedTest
  @EnumSource(MissingPlayerOperation.class)
  void absentPlayerReturnsNamed404WithoutMoneyChanges(MissingPlayerOperation operation) {
    UUID existing = player();
    UUID missing = UUID.randomUUID();
    wallets.credit(existing, 100, "test", "fund", "test", ref());
    var before = snapshot();
    rejection(
        () -> {
          switch (operation) {
            case CREDIT -> wallets.credit(missing, 1, "test", "grant", "test", ref());
            case DEBIT -> wallets.debit(missing, 1, "test", "buy", "test", ref());
            case BALANCE -> wallets.balance(missing);
            case HISTORY -> wallets.history(missing, null, 10);
            case TRANSFER_SENDER ->
                wallets.transfer(missing, existing, 1, "test", "gift", "test", ref());
            case TRANSFER_RECIPIENT ->
                wallets.transfer(existing, missing, 1, "test", "gift", "test", ref());
          }
        },
        404,
        "PLAYER_NOT_FOUND");
    assertThat(snapshot()).isEqualTo(before);
  }

  enum SuspendedOperation {
    CREDIT,
    DEBIT,
    TRANSFER_SENDER,
    TRANSFER_RECIPIENT,
    REFUND
  }

  @ParameterizedTest
  @EnumSource(SuspendedOperation.class)
  void suspendedPlayerCannotChangeEitherWallet(SuspendedOperation operation) {
    UUID suspended = player();
    UUID active = player();
    UUID original =
        (UUID) wallets.credit(suspended, 100, "test", "fund", "test", ref()).get("transactionId");
    wallets.credit(active, 100, "test", "fund", "test", ref());
    administratorJdbc().update("update player set status='SUSPENDED' where player_id=?", suspended);
    var before = snapshot();
    rejection(
        () -> {
          switch (operation) {
            case CREDIT -> wallets.credit(suspended, 1, "test", "grant", "test", ref());
            case DEBIT -> wallets.debit(suspended, 1, "test", "buy", "test", ref());
            case TRANSFER_SENDER ->
                wallets.transfer(suspended, active, 1, "test", "gift", "test", ref());
            case TRANSFER_RECIPIENT ->
                wallets.transfer(active, suspended, 1, "test", "gift", "test", ref());
            case REFUND -> wallets.refund(original, "test", "cancel", "test", ref());
          }
        },
        409,
        "PLAYER_SUSPENDED");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void duplicatePlayerAndSelfTransferRejectBeforeChangingState() {
    UUID player = player();
    wallets.credit(player, 10, "test", "fund", "test", ref());
    var before = snapshot();
    rejection(() -> wallets.provision(player), 409, "PLAYER_EXISTS");
    rejection(
        () -> wallets.transfer(player, player, 1, "test", "gift", "test", ref()),
        400,
        "SELF_TRANSFER");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void duplicateTransferReferenceRejectsWithoutAnotherPosting() {
    UUID sender = player();
    UUID recipient = player();
    wallets.credit(sender, 100, "test", "fund", "test", ref());
    String reference = ref();
    wallets.transfer(sender, recipient, 10, "test", "gift", "test", reference);
    var before = snapshot();
    rejection(
        () -> wallets.transfer(sender, recipient, 10, "test", "gift", "test", reference),
        409,
        "BUSINESS_REFERENCE_USED");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void duplicateRefundReferenceCannotReverseAnotherOriginal() {
    UUID player = player();
    UUID first =
        (UUID) wallets.credit(player, 10, "test", "fund", "test", ref()).get("transactionId");
    UUID second =
        (UUID) wallets.credit(player, 10, "test", "fund", "test", ref()).get("transactionId");
    String reference = ref();
    wallets.refund(first, "test", "cancel", "test", reference);
    var before = snapshot();
    rejection(
        () -> wallets.refund(second, "test", "cancel", "test", reference),
        409,
        "BUSINESS_REFERENCE_USED");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(wallets.refund(second, "test", "cancel", "test", ref()))
        .containsEntry("balanceAfter", 0L);
  }

  @Test
  void absentAndUnsupportedRefundOriginalsReturnNamedRejections() {
    UUID sender = player();
    UUID recipient = player();
    UUID credit =
        (UUID) wallets.credit(sender, 100, "test", "fund", "test", ref()).get("transactionId");
    UUID debit =
        (UUID) wallets.debit(sender, 20, "test", "buy", "test", ref()).get("transactionId");
    UUID refund =
        (UUID) wallets.refund(debit, "test", "cancel", "test", ref()).get("transactionId");
    UUID transfer =
        (UUID)
            wallets
                .transfer(sender, recipient, 1, "test", "gift", "test", ref())
                .get("transactionId");
    var before = snapshot();
    rejection(
        () -> wallets.refund(UUID.randomUUID(), "test", "cancel", "test", ref()),
        404,
        "TRANSACTION_NOT_FOUND");
    rejection(
        () -> wallets.refund(refund, "test", "cancel", "test", ref()), 409, "REFUND_NOT_SUPPORTED");
    rejection(
        () -> wallets.refund(transfer, "test", "cancel", "test", ref()),
        409,
        "REFUND_NOT_SUPPORTED");
    rejection(
        () -> wallets.refund(debit, "test", "cancel", "test", ref()), 409, "ALREADY_REFUNDED");
    rejection(
        () -> wallets.refund(credit, "test", "cancel", "test", ref()), 409, "INSUFFICIENT_FUNDS");
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"0,", "101,", "20,0", "20,-1"})
  void invalidServicePaginationHasNamedRejection(int limit, Long cursor) {
    UUID player = player();
    var before = snapshot();
    rejection(() -> wallets.history(player, cursor, limit), 400, "INVALID_PAGINATION");
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({
    "TRANSFER,actor,200",
    "TRANSFER,reason,500",
    "TRANSFER,source,100",
    "TRANSFER,reference,200",
    "REFUND,actor,200",
    "REFUND,reason,500",
    "REFUND,source,100",
    "REFUND,reference,200"
  })
  void postingMetadataIsValidatedAtTheServiceBoundary(String operation, String field, int maximum) {
    UUID sender = player();
    UUID recipient = player();
    UUID original =
        (UUID) wallets.credit(sender, 100, "test", "fund", "test", ref()).get("transactionId");
    var metadata =
        new LinkedHashMap<>(
            Map.of("actor", "test", "reason", "reason", "source", "test", "reference", ref()));
    Supplier<Map<String, Object>> posting =
        () -> {
          if (operation.equals("TRANSFER"))
            return wallets.transfer(
                sender,
                recipient,
                1,
                metadata.get("actor"),
                metadata.get("reason"),
                metadata.get("source"),
                metadata.get("reference"));
          return wallets.refund(
              original,
              metadata.get("actor"),
              metadata.get("reason"),
              metadata.get("source"),
              metadata.get("reference"));
        };
    String suffix = ref();
    String atLimit = "x".repeat(maximum - suffix.length()) + suffix;
    var before = snapshot();
    for (String invalid : Arrays.asList(null, " ", atLimit + "x")) {
      metadata.put(field, invalid);
      rejection(posting::get, 400, "INVALID_INPUT");
      assertThat(snapshot()).isEqualTo(before);
    }
    metadata.put(field, atLimit);
    var receipt = assertDoesNotThrow(posting::get, "metadata at the stated limit must be accepted");
    assertThat(
            jdbc.queryForMap(
                "select actor,reason,source,reference from journal_transaction where transaction_id=?",
                receipt.get("transactionId")))
        .isEqualTo(metadata);
    assertThat(wallets.balance(sender))
        .containsEntry("balance", operation.equals("TRANSFER") ? 99L : 0L);
    assertThat(wallets.balance(recipient))
        .containsEntry("balance", operation.equals("TRANSFER") ? 1L : 0L);
  }

  @Test
  void requiredWalletAndPostingIdentifiersHaveNamedValidation() {
    UUID player = player();
    var before = snapshot();
    rejection(() -> wallets.provision(null), 400, "INVALID_INPUT");
    rejection(() -> wallets.balance(null), 400, "INVALID_INPUT");
    rejection(
        () -> wallets.transfer(null, player, 1, "test", "gift", "test", ref()),
        400,
        "INVALID_INPUT");
    rejection(
        () -> wallets.transfer(player, null, 1, "test", "gift", "test", ref()),
        400,
        "INVALID_INPUT");
    rejection(() -> wallets.refund(null, "test", "cancel", "test", ref()), 400, "INVALID_INPUT");
    assertThat(snapshot()).isEqualTo(before);
  }

  private void rejection(Runnable action, int status, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(status);
              assertThat(error.code()).isEqualTo(code);
            });
  }

  private Map<String, Object> snapshot() {
    return Map.of(
        "wallets",
            jdbc.queryForList("select wallet_id,balance,sequence from wallet order by wallet_id"),
        "players", jdbc.queryForObject("select count(*) from player", Long.class),
        "accounts", jdbc.queryForObject("select count(*) from ledger_account", Long.class),
        "journals", jdbc.queryForObject("select count(*) from journal_transaction", Long.class),
        "entries", jdbc.queryForObject("select count(*) from ledger_entry", Long.class),
        "outbox", jdbc.queryForObject("select count(*) from outbox_event", Long.class));
  }

  private UUID player() {
    UUID id = UUID.randomUUID();
    wallets.provision(id);
    return id;
  }

  private String ref() {
    return UUID.randomUUID().toString();
  }
}
