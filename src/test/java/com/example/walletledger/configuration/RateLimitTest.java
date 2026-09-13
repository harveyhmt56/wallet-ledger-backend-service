package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimitTest {
  @Test
  void rejectsOnlyRequestsAboveLimit() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(), any())).thenReturn(1L, 2L, 3L);
    var limiter = new RedisRateLimiter(redis, new SimpleMeterRegistry(), 2, 60);
    assertThat(limiter.allow("alice")).isTrue();
    assertThat(limiter.allow("alice")).isTrue();
    assertThat(limiter.allow("alice")).isFalse();
  }

  @Test
  void missingRedisResultAllowsRequestAndRecordsDegradedOperation() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(), any())).thenReturn(null);
    var metrics = new SimpleMeterRegistry();

    assertThat(new RedisRateLimiter(redis, metrics, 2, 60).allow("alice")).isTrue();
    assertThat(metrics.counter("wallet.rate_limit.degraded").count()).isEqualTo(1);
  }

  @Test
  void redisFailureAllowsRequestAndRecordsDegradedOperation() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(), anyList(), any(), any()))
        .thenThrow(new RedisConnectionFailureException("offline"));
    var metrics = new SimpleMeterRegistry();
    assertThat(new RedisRateLimiter(redis, metrics, 2, 60).allow("alice")).isTrue();
    assertThat(metrics.counter("wallet.rate_limit.degraded").count()).isEqualTo(1);
  }
}
