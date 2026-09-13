package com.example.walletledger.idempotency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.walletledger.wallet.domain.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class CommandExecutorTest {
  @ParameterizedTest(name = "rejects {0} before reserving a key")
  @MethodSource("invalidIdentities")
  void rejectsInvalidCallerOrKeyBeforeAnyBusinessOrPersistenceWork(
      String description, String actor, String key) {
    var jdbc = mock(JdbcClient.class);
    var transactions = mock(PlatformTransactionManager.class);
    var commands =
        new CommandExecutor(new ObjectMapper(), jdbc, transactions, new SimpleMeterRegistry());
    var actions = new AtomicInteger();

    assertThatThrownBy(
            () ->
                commands.execute(
                    actor,
                    key,
                    "probe",
                    Map.of(),
                    () -> Map.of("calls", actions.incrementAndGet())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_INPUT");
              assertThat(error.getMessage()).isNotBlank();
            });

    assertThat(actions).hasValue(0);
    verifyNoInteractions(jdbc, transactions);
  }

  static Stream<Arguments> invalidIdentities() {
    return Stream.of(
        Arguments.of("null caller", null, "key"),
        Arguments.of("empty caller", "", "key"),
        Arguments.of("blank caller", " \t\n", "key"),
        Arguments.of("oversized caller", "a".repeat(201), "key"),
        Arguments.of("null key", "caller", null),
        Arguments.of("empty key", "caller", ""),
        Arguments.of("blank key", "caller", " \t\n"),
        Arguments.of("oversized key", "caller", "k".repeat(201)));
  }
}
