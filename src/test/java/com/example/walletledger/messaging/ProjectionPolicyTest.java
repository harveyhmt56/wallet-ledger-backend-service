package com.example.walletledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.messaging.domain.ProjectionPolicy;
import org.junit.jupiter.api.Test;

class ProjectionPolicyTest {
  @Test
  void newerSnapshotAdvancesButDuplicateAndOlderEventsCannotRewindBalance() {
    assertThat(ProjectionPolicy.shouldApply(4, 5)).isTrue();
    assertThat(ProjectionPolicy.shouldApply(4, 4)).isFalse();
    assertThat(ProjectionPolicy.shouldApply(4, 3)).isFalse();
    assertThat(ProjectionPolicy.shouldApply(0, 1)).isTrue();
    assertThat(ProjectionPolicy.shouldApply(4, 7)).isTrue();
  }
}
