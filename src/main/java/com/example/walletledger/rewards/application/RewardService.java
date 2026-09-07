package com.example.walletledger.rewards.application;

import com.example.walletledger.rewards.domain.DailyRewardPolicy;
import com.example.walletledger.rewards.infrastructure.RewardRepository;
import com.example.walletledger.rewards.infrastructure.RewardRepository.Definition;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
public class RewardService {
  private final RewardRepository repository;
  private final WalletService wallets;
  private final Clock clock;

  public RewardService(RewardRepository repository, WalletService wallets, Clock clock) {
    this.repository = repository;
    this.wallets = wallets;
    this.clock = clock;
  }

  public Map<String, Object> daily(UUID playerId, String actor) {
    requirePlayer(playerId);
    var state = repository.lockStreak(playerId);
    LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    var award = DailyRewardPolicy.next(state.lastClaimDate(), state.streak(), date);
    var receipt =
        new LinkedHashMap<>(
            wallets.credit(
                playerId,
                award.amount(),
                actor,
                "Daily login reward, day " + award.streak(),
                "DAILY_LOGIN",
                playerId + "/" + date));
    repository.saveDaily(playerId, date, award, transactionId(receipt));
    receipt.put("claimDate", date.toString());
    receipt.put("streak", award.streak());
    receipt.put("policyVersion", award.policyVersion());
    return receipt;
  }

  public Map<String, Object> claim(
      UUID playerId, UUID rewardId, String completionReference, String actor) {
    requirePlayer(playerId);
    UUID completionId;
    try {
      completionId = UUID.fromString(completionReference);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new BusinessException(
          400, "INVALID_COMPLETION_REFERENCE", "Completion reference must be a UUID");
    }
    var completion =
        repository
            .lockCompletion(completionId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        404, "COMPLETION_NOT_FOUND", "Trusted completion does not exist"));
    if (!completion.playerId().equals(playerId)) {
      throw new BusinessException(
          403, "COMPLETION_NOT_OWNED", "Completion belongs to another player");
    }
    if (!completion.rewardId().equals(rewardId)) {
      throw new BusinessException(
          409, "COMPLETION_REWARD_MISMATCH", "Completion is for a different reward");
    }
    if (repository.completionClaimed(completionId)) {
      throw new BusinessException(
          409, "REWARD_ALREADY_CLAIMED", "Completion reward was already claimed");
    }
    var definition = requireReward(rewardId);
    var receipt =
        new LinkedHashMap<>(
            wallets.credit(
                playerId,
                definition.amount(),
                actor,
                "Trusted completion reward",
                "REWARD_CLAIM",
                completionId.toString()));
    repository.saveClaim(completion, definition, transactionId(receipt), clock.instant());
    receipt.put("rewardId", rewardId.toString());
    receipt.put("completionReference", completionId.toString());
    receipt.put("policyVersion", definition.policyVersion());
    return receipt;
  }

  public Map<String, Object> promotion(UUID playerId, UUID promotionId, String actor) {
    requirePlayer(playerId);
    var campaign =
        repository
            .lockCampaign(promotionId)
            .orElseThrow(
                () ->
                    new BusinessException(404, "PROMOTION_NOT_FOUND", "Promotion does not exist"));
    if (!campaign.enabled()) {
      throw new BusinessException(409, "PROMOTION_DISABLED", "Promotion is disabled");
    }
    if (repository.promotionClaimed(promotionId, playerId)) {
      throw new BusinessException(
          409, "PROMOTION_ALREADY_CLAIMED", "Player already claimed this promotion");
    }
    if (campaign.claimedCount() >= campaign.capacity()) {
      throw new BusinessException(
          409, "PROMOTION_EXHAUSTED", "Promotion has no remaining capacity");
    }
    var receipt =
        new LinkedHashMap<>(
            wallets.credit(
                playerId,
                campaign.amount(),
                actor,
                "Limited promotion reward",
                "PROMOTION",
                promotionId + "/" + playerId));
    repository.savePromotion(
        promotionId, playerId, campaign, transactionId(receipt), clock.instant());
    receipt.put("promotionId", promotionId.toString());
    receipt.put("policyVersion", campaign.policyVersion());
    return receipt;
  }

  public Map<String, Object> completion(
      UUID playerId, UUID rewardId, String source, String reference, String actor) {
    requirePlayer(playerId);
    requireReward(rewardId);
    validateText(source, 100, "Source");
    validateText(reference, 200, "Reference");
    validateText(actor, 200, "Actor");
    var completion =
        repository.recordCompletion(playerId, rewardId, source, reference, actor, clock.instant());
    if (!completion.playerId().equals(playerId) || !completion.rewardId().equals(rewardId)) {
      throw new BusinessException(
          409,
          "COMPLETION_REFERENCE_REUSED",
          "Source event already identifies a different completion");
    }
    return Map.of(
        "completionReference",
        completion.id().toString(),
        "playerId",
        playerId.toString(),
        "rewardId",
        rewardId.toString(),
        "source",
        source,
        "reference",
        reference);
  }

  private void requirePlayer(UUID playerId) {
    String status =
        repository
            .playerStatus(playerId)
            .orElseThrow(
                () -> new BusinessException(404, "PLAYER_NOT_FOUND", "Player does not exist"));
    if (!"ACTIVE".equals(status)) {
      throw new BusinessException(409, "PLAYER_SUSPENDED", "Player is suspended");
    }
  }

  private Definition requireReward(UUID rewardId) {
    var definition =
        repository
            .definition(rewardId)
            .orElseThrow(
                () -> new BusinessException(404, "REWARD_NOT_FOUND", "Reward does not exist"));
    if (!definition.enabled()) {
      throw new BusinessException(409, "REWARD_DISABLED", "Reward is disabled");
    }
    return definition;
  }

  private static UUID transactionId(Map<String, Object> receipt) {
    return UUID.fromString(receipt.get("transactionId").toString());
  }

  private static void validateText(String value, int maximum, String field) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new BusinessException(
          400, "INVALID_INPUT", field + " must be nonblank and at most " + maximum + " characters");
    }
  }
}
