package com.example.walletledger.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.domain.BusinessException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CommandExecutorIT extends PostgresIntegrationTest {
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;

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
