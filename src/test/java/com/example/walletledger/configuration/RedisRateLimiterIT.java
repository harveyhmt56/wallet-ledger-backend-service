package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

class RedisRateLimiterIT {
  @Test
  void counterHasAnExpiryAndSharedCallersUseSameQuota() {
    try (var redis = new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379)) {
      redis.start();
      var connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
      connection.afterPropertiesSet();
      connection.start();
      try {
        var template = new StringRedisTemplate(connection);
        var first = new RedisRateLimiter(template, new SimpleMeterRegistry(), 2, 60);
        var second = new RedisRateLimiter(template, new SimpleMeterRegistry(), 2, 60);
        String caller = UUID.randomUUID().toString();
        assertThat(first.allow(caller)).isTrue();
        assertThat(second.allow(caller)).isTrue();
        assertThat(first.allow(caller)).isFalse();
        var keys = template.keys("wallet:rate:*");
        assertThat(keys).hasSize(1);
        assertThat(template.getExpire(keys.iterator().next())).isBetween(1L, 60L);
      } finally {
        connection.destroy();
      }
    }
  }
}
