package com.example.walletledger.rewards;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.rewards.application.RewardService;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class RewardRacesIT extends PostgresIntegrationTest {
  static final UUID REWARD = UUID.fromString("20000000-0000-0000-0000-000000000001");
  @Autowired RewardService rewards;
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Clock clock;

  @BeforeEach
  void time() {
    when(clock.instant()).thenReturn(Instant.parse("2026-09-07T23:59:59Z"));
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  }

  @Test
  void midnightAdvancesStreakMissedDayResetsAndSameDayIsUnique() {
    UUID player = player();
    assertThat(rewards.daily(player, "test"))
        .containsEntry("streak", 1)
        .containsEntry("amount", 10L);
    assertThatThrownBy(() -> rewards.daily(player, "test"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("DAILY_ALREADY_CLAIMED"));
    when(clock.instant()).thenReturn(Instant.parse("2026-09-08T00:00:00Z"));
    assertThat(rewards.daily(player, "test"))
        .containsEntry("streak", 2)
        .containsEntry("amount", 20L);
    when(clock.instant()).thenReturn(Instant.parse("2026-09-10T00:00:00Z"));
    assertThat(rewards.daily(player, "test"))
        .containsEntry("streak", 1)
        .containsEntry("amount", 10L);
    assertThat(wallets.balance(player)).containsEntry("balance", 40L);
  }

  @Test
  void differentKeysCannotClaimOneTrustedCompletionTwice() throws Exception {
    UUID player = player();
    String completion =
        rewards
            .completion(player, REWARD, "server", UUID.randomUUID().toString(), "service")
            .get("completionReference")
            .toString();
    var results =
        concurrent(
            30,
            n ->
                commands.execute(
                    "player",
                    UUID.randomUUID().toString(),
                    "claim",
                    Map.of("completion", completion),
                    () -> rewards.claim(player, REWARD, completion, "player")));
    assertThat(results).filteredOn(result -> result.status() == 200).hasSize(1);
    assertThat(results)
        .filteredOn(result -> result.status() == 409)
        .hasSize(29)
        .allSatisfy(
            result ->
                assertThat(result.body().path("code").asText())
                    .isEqualTo("REWARD_ALREADY_CLAIMED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reward_claim where completion_id=?",
                Long.class,
                UUID.fromString(completion)))
        .isEqualTo(1);
    assertThat(wallets.balance(player))
        .containsEntry("balance", 100L)
        .containsEntry("sequence", 1L);
    UUID another = player();
    assertThatThrownBy(() -> rewards.claim(another, REWARD, completion, "another"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("COMPLETION_NOT_OWNED"));
  }

  @Test
  void fiveHundredPlayersCompeteForExactlyOneHundredDistinctSlots() throws Exception {
    UUID promotion = campaign(100, 25);
    var players = new ArrayList<UUID>();
    for (int i = 0; i < 500; i++) players.add(player());
    var results =
        concurrent(
            500,
            n ->
                commands.execute(
                    "test",
                    UUID.randomUUID().toString(),
                    "promotion",
                    Map.of("player", players.get(n)),
                    () -> rewards.promotion(players.get(n), promotion, "test")));
    assertThat(results).filteredOn(result -> result.status() == 200).hasSize(100);
    assertThat(results)
        .filteredOn(result -> result.status() == 409)
        .hasSize(400)
        .allSatisfy(
            result ->
                assertThat(result.body().path("code").asText()).isEqualTo("PROMOTION_EXHAUSTED"));
    assertThat(
            jdbc.queryForObject(
                "select count(distinct player_id) from promotion_claim where promotion_id=?",
                Long.class,
                promotion))
        .isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "select claimed_count from promotion where promotion_id=?",
                Integer.class,
                promotion))
        .isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "select sum(amount) from promotion_claim where promotion_id=?",
                Long.class,
                promotion))
        .isEqualTo(2500);
    assertThat(wallets.reconciliation()).containsEntry("consistent", true);
  }

  @Test
  void failedRewardPostingConsumesNoEntitlementOrCapacity() {
    UUID player = player();
    wallets.credit(player, Long.MAX_VALUE, "test", "max", "test", UUID.randomUUID().toString());
    UUID promotion = campaign(1, 25);
    assertThatThrownBy(() -> rewards.promotion(player, promotion, "test"))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("BALANCE_LIMIT"));
    assertThat(
            jdbc.queryForObject(
                "select claimed_count from promotion where promotion_id=?",
                Integer.class,
                promotion))
        .isZero();
    assertThatThrownBy(() -> rewards.daily(player, "test"))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("BALANCE_LIMIT"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from daily_streak where player_id=?", Long.class, player))
        .isZero();
    String completion =
        rewards
            .completion(player, REWARD, "test", UUID.randomUUID().toString(), "test")
            .get("completionReference")
            .toString();
    assertThatThrownBy(() -> rewards.claim(player, REWARD, completion, "test"))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("BALANCE_LIMIT"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reward_claim where completion_id=?",
                Long.class,
                UUID.fromString(completion)))
        .isZero();
  }

  @Test
  void featurePersistenceFailureRollsBackCreditAndCanBeRetried() {
    UUID player = player();
    String completion =
        rewards
            .completion(player, REWARD, "test", UUID.randomUUID().toString(), "test")
            .get("completionReference")
            .toString();
    var owner = migrationJdbc();
    owner.execute(
        "create function fail_test_claim() returns trigger language plpgsql as $$ begin if NEW.player_id='"
            + player
            + "'::uuid then raise exception 'injected claim failure'; end if; return NEW; end $$");
    owner.execute(
        "create trigger fail_test_claim before insert on reward_claim for each row execute function fail_test_claim()");
    try {
      assertThatThrownBy(() -> rewards.claim(player, REWARD, completion, "test"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(wallets.balance(player))
          .containsEntry("balance", 0L)
          .containsEntry("sequence", 0L);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from outbox_event where wallet_id=?", Long.class, player))
          .isZero();
    } finally {
      owner.execute("drop trigger fail_test_claim on reward_claim");
      owner.execute("drop function fail_test_claim()");
    }
    assertThat(rewards.claim(player, REWARD, completion, "test")).containsEntry("amount", 100L);
  }

  UUID player() {
    UUID id = UUID.randomUUID();
    wallets.provision(id);
    return id;
  }

  UUID campaign(int capacity, long amount) {
    UUID id = UUID.randomUUID();
    migrationJdbc()
        .update(
            "insert into promotion(promotion_id,name,amount,capacity,policy_version) values (?,'test',?,?,'test-v1')",
            id,
            amount,
            capacity);
    return id;
  }

  <T> List<T> concurrent(int count, IntFunction<T> action) throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<T>>();
      for (int i = 0; i < count; i++) {
        int n = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return action.apply(n);
                }));
      }
      start.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) results.add(future.get(90, TimeUnit.SECONDS));
      return results;
    }
  }
}
