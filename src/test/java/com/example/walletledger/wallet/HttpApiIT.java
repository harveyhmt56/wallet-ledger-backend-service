package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HttpApiIT extends PostgresIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;

  @Test
  void oversizedSourceIsRejectedBeforeReservingIdempotencyKey() throws Exception {
    UUID player = provision();
    String key = UUID.randomUUID().toString();
    String path = "/v1/wallets/" + player + "/credits";
    postCommand(
        path,
        key,
        "{\"amount\":1,\"reason\":\"test\",\"source\":\""
            + "x".repeat(101)
            + "\",\"reference\":\"test\"}",
        400);
    postCommand(path, key, amount(1, UUID.randomUUID().toString()), 200);
  }

  @Test
  void creditReceiptReplaysAfterLaterDebitAndHistoryKeepsStableCursor() throws Exception {
    UUID player = provision();
    String path = "/v1/wallets/" + player;
    String key = UUID.randomUUID().toString();
    String body = amount(100, "award");
    var original = postCommand(path + "/credits", key, body, 200);
    postCommand(path + "/debits", UUID.randomUUID().toString(), amount(30, "purchase"), 200);
    assertThat(postCommand(path + "/credits", key, body, 200)).isEqualTo(original);
    postCommand(path + "/credits", key, amount(101, "award"), 409);
    http.perform(get(path + "/balance").with(user(player.toString()).roles("PLAYER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(70));
    var first =
        http.perform(
                get(path + "/transactions?limit=1").with(user(player.toString()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andReturn();
    var page = json.readTree(first.getResponse().getContentAsString());
    assertThat(page.path("items")).hasSize(1);
    postCommand(path + "/credits", UUID.randomUUID().toString(), amount(1, "new"), 200);
    var second =
        http.perform(
                get(path + "/transactions?limit=1&cursor=" + page.path("nextCursor").asLong())
                    .with(user(player.toString()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json.readTree(second.getResponse().getContentAsString())
                .path("items")
                .get(0)
                .path("transactionId"))
        .isNotEqualTo(page.path("items").get(0).path("transactionId"));
  }

  @Test
  void businessRejectionIsPersistentButMalformedRequestsDoNotReserveKey() throws Exception {
    UUID player = provision();
    String path = "/v1/wallets/" + player;
    String key = UUID.randomUUID().toString();
    String debit = amount(10, "buy");
    JsonNode rejected = postCommand(path + "/debits", key, debit, 409);
    assertThat(rejected.path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    postCommand(path + "/credits", UUID.randomUUID().toString(), amount(100, "fund"), 200);
    assertThat(postCommand(path + "/debits", key, debit, 409)).isEqualTo(rejected);
    String validationKey = UUID.randomUUID().toString();
    for (String invalid :
        new String[] {"0", "-1", "1.5", "9223372036854775808", "\"10\"", "null"}) {
      postCommand(
          path + "/credits",
          validationKey,
          "{\"amount\":"
              + invalid
              + ",\"reason\":\"test\",\"source\":\"test\",\"reference\":\"invalid\"}",
          400);
    }
    postCommand(path + "/credits", validationKey, amount(1, "valid"), 200);
  }

  @Test
  void playerCannotReadAnotherWalletOrMintCurrencyAndAnonymousIsRejected() throws Exception {
    UUID player = provision();
    String path = "/v1/wallets/" + player;
    http.perform(get(path + "/balance"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    http.perform(get(path + "/balance").with(user(UUID.randomUUID().toString()).roles("PLAYER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    http.perform(
            post(path + "/credits")
                .with(user(player.toString()).roles("PLAYER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(amount(10, "cheat")))
        .andExpect(status().isForbidden());
    http.perform(
            post(path + "/credits")
                .with(user("service").roles("SERVICE"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(amount(10, "missing-key")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  UUID provision() throws Exception {
    UUID player = UUID.randomUUID();
    postCommand(
        "/v1/players", UUID.randomUUID().toString(), "{\"playerId\":\"" + player + "\"}", 200);
    return player;
  }

  JsonNode postCommand(String path, String key, String body, int expected) throws Exception {
    var result =
        http.perform(
                post(path)
                    .with(user("service").roles("SERVICE"))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is(expected))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  String amount(long amount, String ref) {
    return "{\"amount\":"
        + amount
        + ",\"reason\":\"test\",\"source\":\"http-test\",\"reference\":\""
        + ref
        + "\"}";
  }
}
