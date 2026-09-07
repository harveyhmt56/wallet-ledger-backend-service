package com.example.walletledger.wallet.domain;

/** Whole units only; every accepted result fits the PostgreSQL BIGINT balance column. */
public final class MoneyRules {
  private MoneyRules() {}

  public static long credit(long balance, long amount) {
    positive(amount);
    try {
      return Math.addExact(balance, amount);
    } catch (ArithmeticException exception) {
      throw new BusinessException(
          409, "BALANCE_LIMIT", "The wallet balance limit would be exceeded");
    }
  }

  public static long debit(long balance, long amount) {
    positive(amount);
    if (amount > balance) {
      throw new BusinessException(409, "INSUFFICIENT_FUNDS", "The wallet has insufficient funds");
    }
    return balance - amount;
  }

  public static long nextSequence(long sequence) {
    try {
      return Math.incrementExact(sequence);
    } catch (ArithmeticException exception) {
      throw new BusinessException(
          409, "SEQUENCE_LIMIT", "The wallet sequence limit has been reached");
    }
  }

  private static void positive(long amount) {
    if (amount <= 0) {
      throw new BusinessException(400, "INVALID_AMOUNT", "Amount must be a positive whole number");
    }
  }
}
