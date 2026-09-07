package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.wallet.domain.BusinessException;
import com.example.walletledger.wallet.domain.MoneyRules;
import org.junit.jupiter.api.Test;

class MoneyRulesTest {
  @Test
  void creditsAndDebitsExactWholeUnits() {
    assertThat(MoneyRules.credit(0, 10)).isEqualTo(10);
    assertThat(MoneyRules.credit(12, 10)).isEqualTo(22);
    assertThat(MoneyRules.debit(12, 10)).isEqualTo(2);
    assertThat(MoneyRules.debit(10, 10)).isZero();
    assertThat(MoneyRules.credit(Long.MAX_VALUE - 1, 1)).isEqualTo(Long.MAX_VALUE);
    assertThat(MoneyRules.debit(Long.MAX_VALUE, Long.MAX_VALUE)).isZero();
  }

  @Test
  void amountsMustBeStrictlyPositive() {
    for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
      assertBusiness(() -> MoneyRules.credit(10, amount), 400, "INVALID_AMOUNT");
      assertBusiness(() -> MoneyRules.debit(10, amount), 400, "INVALID_AMOUNT");
    }
  }

  @Test
  void rejectsOverdraftsAndOverflow() {
    assertBusiness(() -> MoneyRules.debit(0, 1), 409, "INSUFFICIENT_FUNDS");
    assertBusiness(() -> MoneyRules.debit(9, 10), 409, "INSUFFICIENT_FUNDS");
    assertBusiness(() -> MoneyRules.credit(Long.MAX_VALUE, 1), 409, "BALANCE_LIMIT");
    assertBusiness(() -> MoneyRules.credit(1, Long.MAX_VALUE), 409, "BALANCE_LIMIT");
  }

  @Test
  void sequenceIsMonotonicAndCannotOverflow() {
    assertThat(MoneyRules.nextSequence(0)).isEqualTo(1);
    assertThat(MoneyRules.nextSequence(Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE);
    assertBusiness(() -> MoneyRules.nextSequence(Long.MAX_VALUE), 409, "SEQUENCE_LIMIT");
  }

  private void assertBusiness(Runnable action, int status, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(status);
              assertThat(error.code()).isEqualTo(code);
              assertThat(error.getMessage()).isNotBlank();
            });
  }
}
