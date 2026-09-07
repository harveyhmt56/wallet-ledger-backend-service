package com.example.walletledger.idempotency;

import com.example.walletledger.wallet.domain.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommandExecutor {
  private final ObjectMapper mapper;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final MeterRegistry metrics;

  public CommandExecutor(
      ObjectMapper mapper,
      JdbcClient jdbc,
      PlatformTransactionManager manager,
      MeterRegistry metrics) {
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.metrics = metrics;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transaction.setTimeout(30);
  }

  public CommandResult execute(
      String actor,
      String key,
      String operation,
      Object payload,
      Supplier<Map<String, Object>> action) {
    if (actor == null
        || actor.isBlank()
        || actor.length() > 200
        || key == null
        || key.isBlank()
        || key.length() > 200) {
      throw new BusinessException(
          400,
          "INVALID_INPUT",
          "Caller and idempotency key must be nonblank and at most 200 characters");
    }
    String fingerprint = fingerprint(operation, payload);
    for (int attempt = 0; ; attempt++) {
      try {
        return transaction.execute(
            status -> {
              jdbc.sql(
                      "insert into idempotency_request(actor,request_key,fingerprint) values (:actor,:key,:fingerprint) on conflict do nothing")
                  .param("actor", actor)
                  .param("key", key)
                  .param("fingerprint", fingerprint)
                  .update();
              var stored =
                  jdbc.sql(
                          "select fingerprint,status,response::text from idempotency_request where actor=:actor and request_key=:key")
                      .param("actor", actor)
                      .param("key", key)
                      .query(
                          (rs, n) ->
                              new Stored(
                                  rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)))
                      .single();
              if (!stored.fingerprint().equals(fingerprint)) {
                return rejected(
                    new BusinessException(
                        409,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for different content"));
              }
              if (stored.status() != null)
                return new CommandResult(stored.status(), parse(stored.response()));
              Object savepoint = status.createSavepoint();
              CommandResult result;
              try {
                result = new CommandResult(200, parse(mapper.valueToTree(action.get()).toString()));
              } catch (BusinessException rejection) {
                status.rollbackToSavepoint(savepoint);
                result = rejected(rejection);
              } finally {
                status.releaseSavepoint(savepoint);
              }
              jdbc.sql(
                      "update idempotency_request set status=:status,response=cast(:response as jsonb) where actor=:actor and request_key=:key")
                  .param("status", result.status())
                  .param("response", result.body().toString())
                  .param("actor", actor)
                  .param("key", key)
                  .update();
              return result;
            });
      } catch (TransientDataAccessException transientFailure) {
        if (attempt >= 2) throw transientFailure;
        metrics.counter("wallet.command.retries").increment();
        try {
          Thread.sleep(20L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw transientFailure;
        }
      }
    }
  }

  private CommandResult rejected(BusinessException failure) {
    metrics.counter("wallet.command.rejections", "code", failure.code()).increment();
    return new CommandResult(
        failure.status(),
        canonical(
            mapper.valueToTree(
                Map.of(
                    "type",
                    "about:blank",
                    "status",
                    failure.status(),
                    "title",
                    failure.code(),
                    "code",
                    failure.code(),
                    "detail",
                    failure.getMessage()))));
  }

  private String fingerprint(String operation, Object payload) {
    try {
      byte[] canonical =
          mapper.writeValueAsBytes(
              canonical(mapper.valueToTree(Map.of("operation", operation, "payload", payload))));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception error) {
      throw new IllegalArgumentException("Cannot fingerprint request", error);
    }
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      names.forEach(name -> sorted.set(name, canonical(node.get(name))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode sorted = mapper.createArrayNode();
      node.forEach(value -> sorted.add(canonical(value)));
      return sorted;
    }
    return node;
  }

  private JsonNode parse(String response) {
    try {
      return canonical(mapper.readTree(response));
    } catch (Exception error) {
      throw new IllegalStateException("Stored idempotency response is invalid", error);
    }
  }

  private record Stored(String fingerprint, Integer status, String response) {}
}
