package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Explicit local measurement: ./mvnw -Dtest=LoadMeasurement test. No timing pass threshold. */
class LoadMeasurement extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired ObjectMapper json;

  @Test
  void measuresManyWalletAndHotWalletTraffic() throws Exception {
    var report = new LinkedHashMap<String, Object>();
    report.put("measuredAt", Instant.now().toString());
    report.put(
        "boundary",
        "Java application + idempotency + local PostgreSQL; excludes HTTP, Redis and Kafka relay");
    report.put("manyWallets", measure(false));
    report.put("hotWallet", measure(true));
    Files.createDirectories(Path.of("target"));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("target/load-report.json").toFile(), report);
    assertThat(wallets.reconciliation()).containsEntry("consistent", true);
  }

  Map<String, Object> measure(boolean hot) throws Exception {
    int count = 200;
    UUID shared = UUID.randomUUID();
    wallets.provision(shared);
    var players = new ArrayList<UUID>();
    for (int i = 0; i < count; i++) {
      UUID id = hot ? shared : UUID.randomUUID();
      if (!hot) wallets.provision(id);
      players.add(id);
    }
    var samples = new AtomicInteger();
    var maxWaiters = new AtomicInteger();
    var start = new CountDownLatch(1);
    var admin = administratorJdbc();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor();
        var monitor = Executors.newSingleThreadScheduledExecutor()) {
      var poll =
          monitor.scheduleAtFixedRate(
              () -> {
                int waits =
                    admin.queryForObject(
                        "select count(*) from pg_stat_activity where datname='wallet_ledger' and wait_event_type='Lock'",
                        Integer.class);
                if (waits > 0) samples.incrementAndGet();
                maxWaiters.accumulateAndGet(waits, Math::max);
              },
              0,
              10,
              TimeUnit.MILLISECONDS);
      var futures = new ArrayList<Future<Long>>();
      for (int i = 0; i < count; i++) {
        int n = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  long began = System.nanoTime();
                  var result =
                      commands.execute(
                          "load",
                          UUID.randomUUID().toString(),
                          "credit",
                          Map.of("player", players.get(n)),
                          () ->
                              wallets.credit(
                                  players.get(n),
                                  1,
                                  "load",
                                  "measure",
                                  "load",
                                  UUID.randomUUID().toString()));
                  assertThat(result.status()).isEqualTo(200);
                  return System.nanoTime() - began;
                }));
      }
      long began = System.nanoTime();
      start.countDown();
      var times = new ArrayList<Long>();
      for (var future : futures) times.add(future.get(60, TimeUnit.SECONDS));
      double elapsed = (System.nanoTime() - began) / 1e9;
      poll.cancel(false);
      times.sort(Long::compare);
      return Map.of(
          "operations",
          count,
          "concurrency",
          count,
          "elapsedSeconds",
          elapsed,
          "operationsPerSecond",
          count / elapsed,
          "p50Millis",
          times.get(99) / 1e6,
          "p95Millis",
          times.get(189) / 1e6,
          "p99Millis",
          times.get(197) / 1e6,
          "lockWaitSamples",
          samples.get(),
          "maxConcurrentLockWaiters",
          maxWaiters.get());
    }
  }
}
