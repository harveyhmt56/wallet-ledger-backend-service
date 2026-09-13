package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ProvisionConcurrencyIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;

  @Test
  void competingProvisionsReturnOnePlayerAndOneNamedConflict() throws Exception {
    UUID player = UUID.randomUUID();
    String tag = "provision-race-" + UUID.randomUUID();
    var results = new ArrayList<CommandResult>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor();
        var holder = administratorJdbc().getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement = holder.createStatement()) {
        // SHARE permits the existence query but holds both contenders before their INSERT.
        statement.execute("lock table player in share mode");
      }
      try {
        var first = pool.submit(() -> provision(player, tag + "-a"));
        awaitBlocked(tag + "-a");
        var second = pool.submit(() -> provision(player, tag + "-b"));
        awaitBlocked(tag + "-b");
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        holder.commit();
        assertThatCode(
                () -> {
                  results.add(first.get(10, TimeUnit.SECONDS));
                  results.add(second.get(10, TimeUnit.SECONDS));
                })
            .as("both provisions return receipts instead of leaking a database exception")
            .doesNotThrowAnyException();
      } finally {
        holder.rollback();
      }
    }
    assertThat(results)
        .filteredOn(r -> r.status() == 200)
        .hasSize(1)
        .allSatisfy(
            r -> {
              assertThat(r.body().path("playerId").asText()).isEqualTo(player.toString());
              assertThat(r.body().path("walletId").asText()).isEqualTo(player.toString());
              assertThat(r.body().path("balance").asLong()).isZero();
              assertThat(r.body().path("sequence").asLong()).isZero();
            });
    assertThat(results)
        .filteredOn(r -> r.status() == 409)
        .hasSize(1)
        .allSatisfy(r -> assertThat(r.body().path("code").asText()).isEqualTo("PLAYER_EXISTS"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from player where player_id=?", Long.class, player))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_account where player_id=?", Long.class, player))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from wallet where player_id=?", Long.class, player))
        .isEqualTo(1);
    assertThat(wallets.balance(player)).containsEntry("balance", 0L).containsEntry("sequence", 0L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entry where wallet_id=?", Long.class, player))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where wallet_id=?", Long.class, player))
        .isZero();
  }

  private CommandResult provision(UUID player, String applicationName) {
    return commands.execute(
        "test",
        UUID.randomUUID().toString(),
        "provision",
        Map.of("playerId", player),
        () -> {
          jdbc.queryForObject(
              "select set_config('application_name', ?, true)", String.class, applicationName);
          return wallets.provision(player);
        });
  }

  private void awaitBlocked(String applicationName) {
    await()
        .atMost(Duration.ofSeconds(4))
        .pollInterval(Duration.ofMillis(10))
        .untilAsserted(
            () ->
                assertThat(
                        administratorJdbc()
                            .queryForObject(
                                "select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid)) > 0",
                                Long.class,
                                applicationName))
                    .isEqualTo(1));
  }
}
