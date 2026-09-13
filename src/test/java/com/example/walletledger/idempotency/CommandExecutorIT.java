package com.example.walletledger.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.domain.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class CommandExecutorIT extends PostgresIntegrationTest {
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;

  @Test
  void retryableFailuresRollbackEveryAttemptAndPersistOnlyTheSuccessfulAttempt() {
    UUID player = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    AtomicInteger attempts = new AtomicInteger();
    double retriesBefore = meters.counter("wallet.command.retries").count();

    CommandResult result =
        commands.execute(
            "test",
            key,
            "retry-probe",
            Map.of(),
            () -> {
              jdbc.update("insert into player(player_id) values (?)", player);
              if (attempts.incrementAndGet() < 3) {
                throw new ConcurrencyFailureException("Injected retryable boundary failure");
              }
              return Map.of("player", player, "attempt", attempts.get());
            });

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().path("attempt").asInt()).isEqualTo(3);
    assertThat(attempts).hasValue(3);
    assertThat(meters.counter("wallet.command.retries").count() - retriesBefore).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from player where player_id=?", Long.class, player))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select status from idempotency_request where actor='test' and request_key=?",
                Integer.class,
                key))
        .isEqualTo(200);
    assertThat(
            commands.execute(
                "test",
                key,
                "retry-probe",
                Map.of(),
                () -> {
                  throw new AssertionError("Successful retry must replay without posting again");
                }))
        .isEqualTo(result);
  }

  @Test
  void exhaustedRetriesLeaveNoReservationOrBusinessWriteAndSameKeyCanRecover() {
    UUID player = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    AtomicInteger attempts = new AtomicInteger();
    var failure = new ConcurrencyFailureException("Injected retryable boundary failure");
    double retriesBefore = meters.counter("wallet.command.retries").count();

    assertThatThrownBy(
            () ->
                commands.execute(
                    "test",
                    key,
                    "retry-probe",
                    Map.of(),
                    () -> {
                      if (attempts.incrementAndGet() > 3) {
                        throw new AssertionError(
                            "A retrying command must stop after three attempts");
                      }
                      jdbc.update("insert into player(player_id) values (?)", player);
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(attempts).hasValue(3);
    assertThat(meters.counter("wallet.command.retries").count() - retriesBefore).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from player where player_id=?", Long.class, player))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_request where actor='test' and request_key=?",
                Long.class,
                key))
        .isZero();
    assertThat(
            commands
                .execute(
                    "test",
                    key,
                    "retry-probe",
                    Map.of(),
                    () -> {
                      jdbc.update("insert into player(player_id) values (?)", player);
                      return Map.of("recovered", true);
                    })
                .status())
        .isEqualTo(200);
  }

  @Test
  void interruptionDuringRetryRollsBackAndPreservesTheInterruptSignal() {
    String key = UUID.randomUUID().toString();
    AtomicInteger attempts = new AtomicInteger();
    var failure = new ConcurrencyFailureException("Injected retryable boundary failure");

    try {
      assertThatThrownBy(
              () ->
                  commands.execute(
                      "test",
                      key,
                      "interrupt-probe",
                      Map.of(),
                      () -> {
                        attempts.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(attempts).hasValue(1);
    } finally {
      Thread.interrupted();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_request where actor='test' and request_key=?",
                Long.class,
                key))
        .isZero();
  }

  @Test
  void nestedObjectOrderIsCanonicalButArrayOrderRemainsPartOfTheRequest() {
    String key = UUID.randomUUID().toString();
    var firstObject = new LinkedHashMap<String, Object>();
    firstObject.put("z", 1);
    firstObject.put("a", 2);
    var reversedObject = new LinkedHashMap<String, Object>();
    reversedObject.put("a", 2);
    reversedObject.put("z", 1);
    Map<String, Object> payload = Map.of("items", List.of(firstObject, List.of(3, 2, 1)));
    CommandResult first =
        commands.execute(
            "test",
            key,
            "array-probe",
            payload,
            () -> Map.of("items", List.of(firstObject, "receipt")));

    assertThat(
            commands.execute(
                "test",
                key,
                "array-probe",
                Map.of("items", List.of(reversedObject, List.of(3, 2, 1))),
                () -> {
                  throw new AssertionError("Object property order must not cause a second action");
                }))
        .isEqualTo(first);
    assertThat(first.body().path("items").get(0).path("z").asInt()).isEqualTo(1);
    assertThat(first.body().path("items").get(1).asText()).isEqualTo("receipt");

    var changed =
        commands.execute(
            "test",
            key,
            "array-probe",
            Map.of("items", List.of(reversedObject, List.of(1, 2, 3))),
            () -> {
              throw new AssertionError(
                  "Changed array order must conflict before running the action");
            });
    assertThat(changed.status()).isEqualTo(409);
    assertThat(changed.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void acceptsCallerAndKeyAtTheMaximumLength() {
    String actor = UUID.randomUUID() + "a".repeat(164);
    String key = UUID.randomUUID() + "k".repeat(164);
    AtomicInteger actions = new AtomicInteger();

    var result =
        commands.execute(
            actor,
            key,
            "length-probe",
            Map.of(),
            () -> Map.of("attempt", actions.incrementAndGet()));

    assertThat(result.status()).isEqualTo(200);
    assertThat(actions).hasValue(1);
    assertThat(
            commands.execute(
                actor,
                key,
                "length-probe",
                Map.of(),
                () -> {
                  throw new AssertionError("Maximum-length identity must replay normally");
                }))
        .isEqualTo(result);
  }

  @Test
  void sameKeyReplaysOriginalReceiptWithoutRunningActionAgain() {
    String key = UUID.randomUUID().toString();
    AtomicInteger actions = new AtomicInteger();
    CommandResult first =
        commands.execute(
            "test",
            key,
            "probe",
            Map.of("nested", Map.of("z", 1, "a", 2)),
            () -> Map.of("receipt", actions.incrementAndGet()));
    CommandResult replay =
        commands.execute(
            "test",
            key,
            "probe",
            Map.of("nested", Map.of("a", 2, "z", 1)),
            () -> Map.of("receipt", actions.incrementAndGet()));
    assertThat(replay).isEqualTo(first);
    assertThat(actions).hasValue(1);
  }

  @Test
  void rollbackToSavepointProtectsBusinessWritesWhileRejectionIsStored() {
    UUID player = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    CommandResult rejected =
        commands.execute(
            "test",
            key,
            "probe",
            Map.of(),
            () -> {
              jdbc.update("insert into player(player_id) values (?)", player);
              throw new BusinessException(409, "NOT_ELIGIBLE", "Not eligible");
            });
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from player where player_id=?", Long.class, player))
        .isZero();
    assertThat(commands.execute("test", key, "probe", Map.of(), () -> Map.of("unexpected", true)))
        .isEqualTo(rejected);
  }

  @Test
  void infrastructureFailureRollsBackReservationAndAllowsFreshRetry() {
    String key = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                commands.execute(
                    "test",
                    key,
                    "probe",
                    Map.of(),
                    () -> {
                      throw new IllegalStateException("Injected persistence boundary failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_request where actor='test' and request_key=?",
                Long.class,
                key))
        .isZero();
    assertThat(commands.execute("test", key, "probe", Map.of(), () -> Map.of("ok", true)).status())
        .isEqualTo(200);
  }
}
