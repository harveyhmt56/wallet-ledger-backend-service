package com.example.walletledger.rewards.domain;

import com.example.walletledger.wallet.domain.BusinessException;
import java.time.LocalDate;

public final class DailyRewardPolicy {
  private DailyRewardPolicy() {}

  public static Award next(LocalDate lastClaimDate, int previousStreak, LocalDate today) {
    if (lastClaimDate != null && !today.isAfter(lastClaimDate)) {
      throw new BusinessException(
          409, "DAILY_ALREADY_CLAIMED", "Today's daily reward was already claimed");
    }
    try {
      int streak = today.minusDays(1).equals(lastClaimDate) ? Math.addExact(previousStreak, 1) : 1;
      return new Award(streak, Math.multiplyExact(10L, streak), "daily-v1");
    } catch (ArithmeticException exception) {
      throw new BusinessException(
          409, "REWARD_LIMIT_EXCEEDED", "Daily reward streak limit reached");
    }
  }

  public record Award(int streak, long amount, String policyVersion) {}
}
