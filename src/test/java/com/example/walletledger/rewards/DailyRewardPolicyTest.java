package com.example.walletledger.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.walletledger.rewards.domain.DailyRewardPolicy;
import com.example.walletledger.wallet.domain.BusinessException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyRewardPolicyTest {
  private final LocalDate today = LocalDate.of(2026, 9, 8);

  @Test
  void firstDayAwardsTenUnitsWithRecordedPolicy() {
    var result = DailyRewardPolicy.next(null, 0, today);
    assertThat(result.streak()).isEqualTo(1);
    assertThat(result.amount()).isEqualTo(10);
    assertThat(result.policyVersion()).isEqualTo("daily-v1");
  }

  @Test
  void consecutiveDayIncreasesStreakAndReward() {
    var result = DailyRewardPolicy.next(today.minusDays(1), 4, today);
    assertThat(result.streak()).isEqualTo(5);
    assertThat(result.amount()).isEqualTo(50);
  }

  @Test
  void missedDayResetsStreak() {
    var result = DailyRewardPolicy.next(today.minusDays(2), 4, today);
    assertThat(result.streak()).isEqualTo(1);
    assertThat(result.amount()).isEqualTo(10);
  }

  @Test
  void sameDayCannotBeClaimedAgain() {
    assertThatThrownBy(() -> DailyRewardPolicy.next(today, 4, today))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.status()).isEqualTo(409);
              assertThat(e.code()).isEqualTo("DAILY_ALREADY_CLAIMED");
            });
  }

  @Test
  void clockRegressionCannotGrantAReward() {
    assertThatThrownBy(() -> DailyRewardPolicy.next(today.plusDays(1), 4, today))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("DAILY_ALREADY_CLAIMED"));
  }

  @Test
  void maximumStreakRejectsInsteadOfWrapping() {
    assertThatThrownBy(() -> DailyRewardPolicy.next(today.minusDays(1), Integer.MAX_VALUE, today))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("REWARD_LIMIT_EXCEEDED"));
  }
}
