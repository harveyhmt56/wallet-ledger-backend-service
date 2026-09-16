package com.example.walletledger.messaging.kafka;

import com.example.walletledger.messaging.domain.ProjectionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceProjection {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public BalanceProjection(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(rollbackFor = Exception.class)
  public void accept(String payload) {
    try {
      var event = json.readTree(payload);
      UUID id = UUID.fromString(event.path("eventId").asText());
      UUID wallet = UUID.fromString(event.path("walletId").asText());
      long version = exactLong(event.path("schemaVersion"));
      long sequence = exactLong(event.path("walletSequence"));
      long balance = exactLong(event.path("balanceAfter"));
      if (version != 1 || sequence <= 0 || balance < 0) {
        throw new IllegalArgumentException("Invalid balance event");
      }
      int inserted =
          jdbc.sql(
                  "insert into consumed_event(event_id,wallet_id) values (:id,:wallet) on conflict do nothing")
              .param("id", id)
              .param("wallet", wallet)
              .update();
      if (inserted == 0) return;
      jdbc.sql(
              "insert into wallet_projection(wallet_id,wallet_sequence,balance) values (:wallet,0,0) on conflict do nothing")
          .param("wallet", wallet)
          .update();
      long current =
          jdbc.sql(
                  "select wallet_sequence from wallet_projection where wallet_id=:wallet for update")
              .param("wallet", wallet)
              .query(Long.class)
              .single();
      if (ProjectionPolicy.shouldApply(current, sequence)) {
        jdbc.sql(
                "update wallet_projection set wallet_sequence=:sequence,balance=:balance where wallet_id=:wallet")
            .param("sequence", sequence)
            .param("balance", balance)
            .param("wallet", wallet)
            .update();
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid balance event JSON", invalid);
    }
  }

  private static long exactLong(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Invalid balance event");
    }
    return value.longValue();
  }
}
