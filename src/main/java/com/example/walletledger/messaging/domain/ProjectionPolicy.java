package com.example.walletledger.messaging.domain;

public final class ProjectionPolicy {
  private ProjectionPolicy() {}

  public static boolean shouldApply(long currentSequence, long incomingSequence) {
    return incomingSequence > currentSequence;
  }
}
