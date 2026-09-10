package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.api.WalletController.TransferRequest;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TransferPrivacyIT extends PostgresIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;

  @Test
  void freshAndReplayedTransferRevealOnlySenderFundsAndPostOnce() throws Exception {
    UUID sender = funded(100);
    UUID recipient = funded(12345);
    String key = UUID.randomUUID().toString();
    var request = request(recipient);

    var receipt = transfer(sender, key, request, 200);
    assertSafeReceipt(receipt, sender, recipient);
    String stored = stored(sender, key);
    wallets.credit(recipient, 50, "service", "later", "privacy", UUID.randomUUID().toString());
    wallets.credit(sender, 20, "service", "later", "privacy", UUID.randomUUID().toString());

    assertThat(transfer(sender, key, request, 200)).isEqualTo(receipt);
    assertThat(stored(sender, key)).isEqualTo(stored);
    assertMoney(sender, recipient, 110, 12405, 3);
  }

  @Test
  void historicalStoredReceiptIsFilteredWithoutRewritingItOrReposting() throws Exception {
    UUID sender = funded(100);
    UUID recipient = funded(12345);
    String key = UUID.randomUUID().toString();
    var request = request(recipient);
    // Seed the exact pre-change receipt using the real command fingerprint and posting boundary.
    var legacy =
        commands.execute(
            sender.toString(),
            key,
            "transfer:" + sender,
            request,
            () ->
                wallets.transfer(
                    sender,
                    recipient,
                    request.amount(),
                    sender.toString(),
                    request.reason(),
                    request.source(),
                    request.reference()));
    assertThat(legacy.body().path("recipientBalanceAfter").asLong()).isEqualTo(12355);
    String stored = stored(sender, key);

    var receipt = transfer(sender, key, request, 200);

    assertSafeReceipt(receipt, sender, recipient);
    assertThat(receipt.path("transactionId")).isEqualTo(legacy.body().path("transactionId"));
    assertThat(receipt.path("occurredAt")).isEqualTo(legacy.body().path("occurredAt"));
    assertThat(transfer(sender, key, request, 200)).isEqualTo(receipt);
    assertThat(stored(sender, key)).isEqualTo(stored);
    assertMoney(sender, recipient, 90, 12355, 2);
  }

  @Test
  void insufficientFundsReplayAndKeyConflictKeepTheirProblemContract() throws Exception {
    UUID sender = funded(1);
    UUID recipient = funded(20);
    String key = UUID.randomUUID().toString();
    var request = request(recipient);

    var rejected = transfer(sender, key, request, 409);
    assertThat(rejected.path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(transfer(sender, key, request, 409)).isEqualTo(rejected);
    var changed =
        new TransferRequest(
            recipient, 11L, request.reason(), request.source(), request.reference());
    assertThat(transfer(sender, key, changed, 409).path("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertMoney(sender, recipient, 1, 20, 1);
  }

  private UUID funded(long amount) {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    wallets.credit(player, amount, "service", "seed", "privacy", UUID.randomUUID().toString());
    return player;
  }

  private TransferRequest request(UUID recipient) {
    return new TransferRequest(recipient, 10L, "gift", "privacy", UUID.randomUUID().toString());
  }

  private JsonNode transfer(UUID sender, String key, TransferRequest request, int status)
      throws Exception {
    var response =
        http.perform(
                post("/v1/transfers")
                    .with(user(sender.toString()).roles("PLAYER"))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)))
            .andExpect(status().is(status))
            .andExpect(
                content()
                    .contentTypeCompatibleWith(
                        status >= 400
                            ? MediaType.APPLICATION_PROBLEM_JSON
                            : MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString());
  }

  private void assertSafeReceipt(JsonNode receipt, UUID sender, UUID recipient) {
    assertThat(receipt.properties())
        .extracting(java.util.Map.Entry::getKey)
        .containsExactlyInAnyOrder(
            "transactionId",
            "operation",
            "amount",
            "reason",
            "occurredAt",
            "playerId",
            "walletId",
            "balanceAfter",
            "walletSequence",
            "recipientId");
    assertThat(receipt.path("playerId").asText()).isEqualTo(sender.toString());
    assertThat(receipt.path("walletId").asText()).isEqualTo(sender.toString());
    assertThat(receipt.path("recipientId").asText()).isEqualTo(recipient.toString());
    assertThat(receipt.path("balanceAfter").asLong()).isEqualTo(90);
    assertThat(receipt.path("walletSequence").asLong()).isEqualTo(2);
    assertThat(receipt.path("amount").asLong()).isEqualTo(10);
    assertThat(receipt.path("operation").asText()).isEqualTo("TRANSFER");
    assertThat(receipt.path("reason").asText()).isEqualTo("gift");
    assertThat(receipt.path("transactionId").asText()).isNotBlank();
    assertThat(receipt.path("occurredAt").asText()).isNotBlank();
  }

  private String stored(UUID sender, String key) {
    return jdbc.queryForObject(
        "select response::text from idempotency_request where actor=? and request_key=?",
        String.class,
        sender.toString(),
        key);
  }

  private void assertMoney(
      UUID sender, UUID recipient, long senderBalance, long recipientBalance, long sequence) {
    assertThat(wallets.balance(sender))
        .containsEntry("balance", senderBalance)
        .containsEntry("sequence", sequence);
    assertThat(wallets.balance(recipient))
        .containsEntry("balance", recipientBalance)
        .containsEntry("sequence", sequence);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entry where wallet_id=?", Long.class, sender))
        .isEqualTo(sequence);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entry where wallet_id=?", Long.class, recipient))
        .isEqualTo(sequence);
  }
}
