package com.example.walletledger.rewards;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.rewards.application.RewardService;
import com.example.walletledger.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RewardServiceIT extends PostgresIntegrationTest {
  private static final UUID REWARD = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID PROMOTION = UUID.fromString("30000000-0000-0000-0000-000000000001");
  @Autowired RewardService rewards;
  @Autowired JdbcClient jdbc;

  @Test
  void firstDailyClaimCreditsTenUnits() {
    UUID player = player();
    assertThat(rewards.daily(player, player.toString()))
        .containsEntry("amount", 10L)
        .containsEntry("streak", 1);
    assertThat(balance(player)).isEqualTo(10);
  }

  @Test
  void trustedCompletionPermitsConfiguredReward() {
    UUID player = player();
    var completion =
        rewards.completion(
            player, REWARD, "mission-server", UUID.randomUUID().toString(), "service");
    assertThat(completion).containsKeys("completionReference", "rewardId", "playerId");
    var receipt =
        rewards.claim(
            player, REWARD, completion.get("completionReference").toString(), player.toString());
    assertThat(receipt).containsEntry("amount", 100L).containsEntry("policyVersion", "mission-v1");
    assertThat(balance(player)).isEqualTo(100);
  }

  @Test
  void promotionCreditsConfiguredAmount() {
    UUID player = player();
    assertThat(rewards.promotion(player, PROMOTION, player.toString()))
        .containsEntry("amount", 25L);
    assertThat(balance(player)).isEqualTo(25);
  }

  private UUID player() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO player(player_id) VALUES (?)").param(id).update();
    jdbc.sql("INSERT INTO ledger_account(account_id,player_id,kind) VALUES (?,?,'PLAYER')")
        .params(id, id)
        .update();
    jdbc.sql("INSERT INTO wallet(wallet_id,player_id,account_id) VALUES (?,?,?)")
        .params(id, id, id)
        .update();
    return id;
  }

  private long balance(UUID player) {
    return jdbc.sql("SELECT balance FROM wallet WHERE player_id=?")
        .param(player)
        .query(Long.class)
        .single();
  }
}
