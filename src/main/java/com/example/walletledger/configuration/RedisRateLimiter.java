package com.example.walletledger.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisRateLimiter {
  private static final DefaultRedisScript<Long> COUNTER =
      new DefaultRedisScript<>(
          "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return n",
          Long.class);
  private final StringRedisTemplate redis;
  private final MeterRegistry meters;
  private final int limit;
  private final int windowSeconds;

  public RedisRateLimiter(
      StringRedisTemplate redis, MeterRegistry meters, int limit, int windowSeconds) {
    this.redis = redis;
    this.meters = meters;
    this.limit = limit;
    this.windowSeconds = windowSeconds;
  }

  public boolean allow(String caller) {
    try {
      String key = "wallet:rate:" + UUID.nameUUIDFromBytes(caller.getBytes(StandardCharsets.UTF_8));
      Long count =
          redis.execute(
              COUNTER, List.of(key), Integer.toString(windowSeconds), Integer.toString(limit));
      if (count == null) {
        meters.counter("wallet.rate_limit.degraded").increment();
        return true;
      }
      return count <= limit;
    } catch (DataAccessException unavailable) {
      meters.counter("wallet.rate_limit.degraded").increment();
      return true;
    }
  }
}
