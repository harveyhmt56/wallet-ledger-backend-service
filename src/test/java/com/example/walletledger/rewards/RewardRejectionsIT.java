package com.example.walletledger.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.walletledger.rewards.application.RewardService;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class RewardRejectionsIT extends PostgresIntegrationTest {
  private static final UUID REWARD = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID PROMOTION = UUID.fromString("30000000-0000-0000-0000-000000000001");
  @Autowired RewardService rewards;
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate jdbc;
  @Autowired NamedParameterJdbcTemplate namedJdbc;
  private final Set<UUID> players = new LinkedHashSet<>();
  private final Set<UUID> definitions = new LinkedHashSet<>(Set.of(REWARD));
  private final Set<UUID> campaigns = new LinkedHashSet<>(Set.of(PROMOTION));

  enum EntryPoint {
    DAILY,
    CLAIM,
    PROMOTION,
    COMPLETION
  }

  @ParameterizedTest
  @EnumSource(EntryPoint.class)
  void missingPlayerIsRejectedBeforeAnyRewardWork(EntryPoint entryPoint) {
    UUID missingPlayer = UUID.randomUUID();
    players.add(missingPlayer);
    reject(404, "PLAYER_NOT_FOUND", () -> invoke(entryPoint, missingPlayer));
  }

  @ParameterizedTest
  @EnumSource(EntryPoint.class)
  void suspendedPlayerCannotCreateEvidenceOrClaimAnyReward(EntryPoint entryPoint) {
    UUID player = player();
    migrationJdbc().update("update player set status='SUSPENDED' where player_id=?", player);
    reject(409, "PLAYER_SUSPENDED", () -> invoke(entryPoint, player));
  }

  private void invoke(EntryPoint entryPoint, UUID player) {
    switch (entryPoint) {
      case DAILY -> rewards.daily(player, "player");
      case CLAIM -> rewards.claim(player, REWARD, UUID.randomUUID().toString(), "player");
      case PROMOTION -> rewards.promotion(player, PROMOTION, "player");
      case COMPLETION -> rewards.completion(player, REWARD, "server", ref(), "service");
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "not-a-uuid"})
  void malformedCompletionReferenceDoesNotReserveAClaim(String reference) {
    UUID player = player();
    reject(
        400,
        "INVALID_COMPLETION_REFERENCE",
        () -> rewards.claim(player, REWARD, reference, "player"));
  }

  @Test
  void unknownCompletionCannotMintAReward() {
    UUID player = player();
    reject(404, "COMPLETION_NOT_FOUND", () -> rewards.claim(player, REWARD, ref(), "player"));
  }

  @Test
  void anotherPlayersCompletionCannotBeClaimed() {
    String completion = completion(player(), REWARD);
    UUID another = player();
    reject(403, "COMPLETION_NOT_OWNED", () -> rewards.claim(another, REWARD, completion, "player"));
  }

  @Test
  void completionForOneRewardCannotClaimADifferentReward() {
    UUID player = player();
    String completion = completion(player, REWARD);
    UUID otherReward = definition();
    reject(
        409,
        "COMPLETION_REWARD_MISMATCH",
        () -> rewards.claim(player, otherReward, completion, "player"));
  }

  @Test
  void unknownRewardCannotRecordCompletion() {
    UUID player = player();
    reject(
        404,
        "REWARD_NOT_FOUND",
        () -> rewards.completion(player, UUID.randomUUID(), "server", ref(), "service"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void disabledRewardCannotRecordEvidenceOrPayAnExistingCompletion(boolean claim) {
    UUID player = player();
    UUID reward = definition();
    String completion = completion(player, reward);
    migrationJdbc().update("update reward_definition set enabled=false where reward_id=?", reward);
    reject(
        409,
        "REWARD_DISABLED",
        () -> {
          if (claim) rewards.claim(player, reward, completion, "player");
          else rewards.completion(player, reward, "server", ref(), "service");
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceReferenceCannotBeReassignedToAnotherPlayerOrReward(boolean changeReward) {
    UUID player = player();
    String reference = ref();
    var original = rewards.completion(player, REWARD, "server", reference, "service");
    UUID nextPlayer = changeReward ? player : player();
    UUID nextReward = changeReward ? definition() : REWARD;
    reject(
        409,
        "COMPLETION_REFERENCE_REUSED",
        () -> rewards.completion(nextPlayer, nextReward, "server", reference, "service"));
    var before = snapshot();
    assertThat(rewards.completion(player, REWARD, "server", reference, "service"))
        .isEqualTo(original);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void unknownPromotionCannotMintCurrency() {
    UUID player = player();
    reject(
        404, "PROMOTION_NOT_FOUND", () -> rewards.promotion(player, UUID.randomUUID(), "player"));
  }

  @Test
  void disabledPromotionPreservesCapacityAndMoney() {
    UUID player = player();
    UUID promotion = campaign(2);
    migrationJdbc().update("update promotion set enabled=false where promotion_id=?", promotion);
    reject(409, "PROMOTION_DISABLED", () -> rewards.promotion(player, promotion, "player"));
  }

  @Test
  void repeatedPromotionClaimIsDistinctFromAnExhaustedCampaign() {
    UUID player = player();
    UUID promotion = campaign(2);
    rewards.promotion(player, promotion, "player");
    reject(409, "PROMOTION_ALREADY_CLAIMED", () -> rewards.promotion(player, promotion, "player"));
    UUID second = player();
    rewards.promotion(second, promotion, "player");
    reject(409, "PROMOTION_ALREADY_CLAIMED", () -> rewards.promotion(player, promotion, "player"));
    UUID latePlayer = player();
    reject(409, "PROMOTION_EXHAUSTED", () -> rewards.promotion(latePlayer, promotion, "player"));
    assertThat(wallets.balance(player)).containsEntry("balance", 25L).containsEntry("sequence", 1L);
    assertThat(wallets.balance(second)).containsEntry("balance", 25L).containsEntry("sequence", 1L);
    assertThat(wallets.balance(latePlayer))
        .containsEntry("balance", 0L)
        .containsEntry("sequence", 0L);
  }

  static Stream<Arguments> invalidMetadata() {
    return Stream.of("source", "reference", "actor")
        .flatMap(
            field ->
                Stream.of(
                    Arguments.of(field, null),
                    Arguments.of(field, ""),
                    Arguments.of(field, " \t"),
                    Arguments.of(field, "x".repeat(field.equals("source") ? 101 : 201))));
  }

  @ParameterizedTest(name = "invalid completion {0}: {1}")
  @MethodSource("invalidMetadata")
  void invalidCompletionMetadataLeavesNoEvidence(String field, String invalid) {
    UUID player = player();
    String source = field.equals("source") ? invalid : "server";
    String reference = field.equals("reference") ? invalid : ref();
    String actor = field.equals("actor") ? invalid : "service";
    reject(
        400, "INVALID_INPUT", () -> rewards.completion(player, REWARD, source, reference, actor));
  }

  @Test
  void completionMetadataAcceptsExactLengthLimits() {
    UUID player = player();
    String source = "s".repeat(100);
    String reference = ref() + "r".repeat(164);
    String actor = "a".repeat(200);
    var receipt = rewards.completion(player, REWARD, source, reference, actor);
    assertThat(receipt).containsEntry("source", source).containsEntry("reference", reference);
    UUID completion = UUID.fromString(receipt.get("completionReference").toString());
    assertThat(
            jdbc.queryForMap(
                "select source,source_reference,actor from action_completion where completion_id=?",
                completion))
        .containsEntry("source", source)
        .containsEntry("source_reference", reference)
        .containsEntry("actor", actor);
    assertThat(rewards.claim(player, REWARD, completion.toString(), "player"))
        .containsEntry("amount", 100L);
    assertThat(wallets.balance(player))
        .containsEntry("balance", 100L)
        .containsEntry("sequence", 1L);
  }

  private void reject(int status, String code, Runnable action) {
    var before = snapshot();
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(status);
              assertThat(error.code()).isEqualTo(code);
              assertThat(error.getMessage()).isNotBlank();
            });
    assertThat(snapshot())
        .as("rejection changes no money, evidence, entitlement, capacity or outbox rows")
        .isEqualTo(before);
  }

  private Map<String, String> snapshot() {
    // PIT can reuse a database containing unrelated long-history fixtures. Compare all columns
    // for this test's entities, including both sides of every affected journal.
    var predicates = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "player",
            "ledger_account",
            "wallet",
            "action_completion",
            "reward_claim",
            "daily_streak",
            "daily_claim")) {
      predicates.put(table, "player_id in (:players)");
    }
    String transactions =
        "transaction_id in (select transaction_id from ledger_entry where wallet_id in (:players))";
    predicates.put("journal_transaction", transactions);
    predicates.put("ledger_entry", transactions);
    predicates.put("outbox_event", "wallet_id in (:players)");
    predicates.put("reward_definition", "reward_id in (:definitions)");
    predicates.put("promotion", "promotion_id in (:campaigns)");
    predicates.put("promotion_claim", "player_id in (:players) or promotion_id in (:campaigns)");
    var parameters = Map.of("players", players, "definitions", definitions, "campaigns", campaigns);
    var result = new LinkedHashMap<String, String>();
    for (var table : predicates.entrySet()) {
      result.put(
          table.getKey(),
          namedJdbc.queryForObject(
              "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "
                  + table.getKey()
                  + " t where "
                  + table.getValue(),
              parameters,
              String.class));
    }
    return result;
  }

  private UUID player() {
    UUID player = UUID.randomUUID();
    players.add(player);
    wallets.provision(player);
    return player;
  }

  private UUID definition() {
    UUID reward = UUID.randomUUID();
    definitions.add(reward);
    migrationJdbc()
        .update(
            "insert into reward_definition(reward_id,name,amount,policy_version) values (?,'Rejection test',100,'test-v1')",
            reward);
    return reward;
  }

  private UUID campaign(int capacity) {
    UUID promotion = UUID.randomUUID();
    campaigns.add(promotion);
    migrationJdbc()
        .update(
            "insert into promotion(promotion_id,name,amount,capacity,policy_version) values (?,'Rejection test',25,?,'test-v1')",
            promotion,
            capacity);
    return promotion;
  }

  private String completion(UUID player, UUID reward) {
    return rewards
        .completion(player, reward, "server", ref(), "service")
        .get("completionReference")
        .toString();
  }

  private String ref() {
    return UUID.randomUUID().toString();
  }
}
