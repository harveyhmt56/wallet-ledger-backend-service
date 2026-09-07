package com.example.walletledger.rewards.infrastructure;

import com.example.walletledger.rewards.domain.DailyRewardPolicy.Award;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RewardRepository {
  private final JdbcClient jdbc;

  public RewardRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> playerStatus(UUID playerId) {
    return jdbc.sql("SELECT status FROM player WHERE player_id=?")
        .param(playerId)
        .query(String.class)
        .optional();
  }

  public Optional<Definition> definition(UUID rewardId) {
    return jdbc.sql("SELECT amount,policy_version,enabled FROM reward_definition WHERE reward_id=?")
        .param(rewardId)
        .query(
            (rs, row) ->
                new Definition(
                    rs.getLong("amount"), rs.getString("policy_version"), rs.getBoolean("enabled")))
        .optional();
  }

  public Streak lockStreak(UUID playerId) {
    jdbc.sql("INSERT INTO daily_streak(player_id) VALUES (?) ON CONFLICT DO NOTHING")
        .param(playerId)
        .update();
    return jdbc.sql("SELECT last_claim_date,streak FROM daily_streak WHERE player_id=? FOR UPDATE")
        .param(playerId)
        .query(
            (rs, row) ->
                new Streak(rs.getObject("last_claim_date", LocalDate.class), rs.getInt("streak")))
        .single();
  }

  public void saveDaily(UUID playerId, LocalDate date, Award award, UUID transactionId) {
    jdbc.sql("UPDATE daily_streak SET last_claim_date=?,streak=? WHERE player_id=?")
        .params(date, award.streak(), playerId)
        .update();
    jdbc.sql(
            """
        INSERT INTO daily_claim(player_id,claim_date,streak,amount,policy_version,transaction_id)
        VALUES (?,?,?,?,?,?)
        """)
        .params(
            playerId, date, award.streak(), award.amount(), award.policyVersion(), transactionId)
        .update();
  }

  public Completion recordCompletion(
      UUID playerId, UUID rewardId, String source, String reference, String actor, Instant now) {
    jdbc.sql(
            """
        INSERT INTO action_completion(completion_id,player_id,reward_id,source,source_reference,actor,completed_at)
        VALUES (?,?,?,?,?,?,?) ON CONFLICT(source,source_reference) DO NOTHING
        """)
        .params(
            UUID.randomUUID(), playerId, rewardId, source, reference, actor, Timestamp.from(now))
        .update();
    return jdbc.sql(
            "SELECT completion_id,player_id,reward_id FROM action_completion WHERE source=? AND source_reference=?")
        .params(source, reference)
        .query(
            (rs, row) ->
                new Completion(
                    rs.getObject("completion_id", UUID.class),
                    rs.getObject("player_id", UUID.class),
                    rs.getObject("reward_id", UUID.class)))
        .single();
  }

  public Optional<Completion> lockCompletion(UUID completionId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param("completion:" + completionId)
        .query((rs, row) -> 0)
        .single();
    return jdbc.sql(
            "SELECT completion_id,player_id,reward_id FROM action_completion WHERE completion_id=?")
        .param(completionId)
        .query(
            (rs, row) ->
                new Completion(
                    rs.getObject("completion_id", UUID.class),
                    rs.getObject("player_id", UUID.class),
                    rs.getObject("reward_id", UUID.class)))
        .optional();
  }

  public boolean completionClaimed(UUID completionId) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM reward_claim WHERE completion_id=?)")
        .param(completionId)
        .query(Boolean.class)
        .single();
  }

  public void saveClaim(
      Completion completion, Definition definition, UUID transactionId, Instant now) {
    jdbc.sql(
            """
        INSERT INTO reward_claim(completion_id,player_id,reward_id,transaction_id,amount,policy_version,claimed_at)
        VALUES (?,?,?,?,?,?,?)
        """)
        .params(
            completion.id(),
            completion.playerId(),
            completion.rewardId(),
            transactionId,
            definition.amount(),
            definition.policyVersion(),
            Timestamp.from(now))
        .update();
  }

  public Optional<Campaign> lockCampaign(UUID promotionId) {
    return jdbc.sql(
            "SELECT amount,capacity,claimed_count,policy_version,enabled FROM promotion WHERE promotion_id=? FOR UPDATE")
        .param(promotionId)
        .query(
            (rs, row) ->
                new Campaign(
                    rs.getLong("amount"),
                    rs.getInt("capacity"),
                    rs.getInt("claimed_count"),
                    rs.getString("policy_version"),
                    rs.getBoolean("enabled")))
        .optional();
  }

  public boolean promotionClaimed(UUID promotionId, UUID playerId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_claim WHERE promotion_id=? AND player_id=?)")
        .params(promotionId, playerId)
        .query(Boolean.class)
        .single();
  }

  public void savePromotion(
      UUID promotionId, UUID playerId, Campaign campaign, UUID transactionId, Instant now) {
    jdbc.sql("UPDATE promotion SET claimed_count=claimed_count+1 WHERE promotion_id=?")
        .param(promotionId)
        .update();
    jdbc.sql(
            """
        INSERT INTO promotion_claim(promotion_id,player_id,transaction_id,amount,policy_version,claimed_at)
        VALUES (?,?,?,?,?,?)
        """)
        .params(
            promotionId,
            playerId,
            transactionId,
            campaign.amount(),
            campaign.policyVersion(),
            Timestamp.from(now))
        .update();
  }

  public record Streak(LocalDate lastClaimDate, int streak) {}

  public record Definition(long amount, String policyVersion, boolean enabled) {}

  public record Completion(UUID id, UUID playerId, UUID rewardId) {}

  public record Campaign(
      long amount, int capacity, int claimedCount, String policyVersion, boolean enabled) {}
}
