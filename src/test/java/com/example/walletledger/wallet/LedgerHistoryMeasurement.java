package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Explicit only: ./mvnw -Dtest=LedgerHistoryMeasurement test. No production latency claim. */
@TestPropertySource(properties = "spring.flyway.target=${ledger.history.target:latest}")
class LedgerHistoryMeasurement extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void measuresCommitInclusivePostingAtIncreasingHistorySizes() throws Exception {
    boolean creditOnly = Boolean.getBoolean("ledger.history.credit-only");
    int samples = Integer.getInteger("ledger.history.samples", 3);
    String version =
        migrationJdbc()
            .queryForObject("select max(version) from public.flyway_schema_history", String.class);
    var report = new LinkedHashMap<String, Object>();
    report.put("measuredAt", Instant.now().toString());
    report.put("schemaVersion", version);
    report.put("postgresql", jdbc.queryForObject("select version()", String.class));
    report.put("java", System.getProperty("java.version"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put(
        "boundary",
        "WalletService and local real PostgreSQL including deferred JDBC COMMIT; excludes HTTP, Redis, Kafka and fixture/audit setup");
    report.put("samplesPerOperation", samples);
    report.put(
        "warmup",
        "Five credit/debit/transfer/refund cycles on two short-history wallets before measurements");

    UUID warmSender = UUID.randomUUID();
    UUID warmRecipient = UUID.randomUUID();
    wallets.provision(warmSender);
    wallets.provision(warmRecipient);
    HistoryFixture.populate(administratorJdbc(), warmSender, 100);
    HistoryFixture.populate(administratorJdbc(), warmRecipient, 100);
    for (int i = 0; i < 5; i++) cycle(warmSender, warmRecipient, false);

    var measurements = new ArrayList<Map<String, Object>>();
    for (int size : new int[] {1_000, 5_000, 10_000, 20_000}) {
      UUID sender = UUID.randomUUID();
      UUID recipient = UUID.randomUUID();
      wallets.provision(sender);
      wallets.provision(recipient);
      HistoryFixture.populate(administratorJdbc(), sender, size);
      HistoryFixture.populate(administratorJdbc(), recipient, size);
      administratorJdbc().execute("ANALYZE public.ledger_entry");
      var timings = new ArrayList<Map<String, Double>>();
      for (int i = 0; i < samples; i++) timings.add(cycle(sender, recipient, creditOnly));
      var measurement = new LinkedHashMap<String, Object>();
      measurement.put("initialEntriesPerWallet", size);
      measurement.put("elapsedMillis", timings);
      measurement.put(
          "predecessorPlan",
          plan(
              """
          SELECT balance_after FROM public.ledger_entry WHERE wallet_id=? AND wallet_sequence=?
          """,
              sender,
              size));
      measurement.put(
          "finalTailPlan",
          plan(
              """
          SELECT wallet_sequence,balance_after FROM public.ledger_entry
          WHERE wallet_id=? ORDER BY wallet_sequence DESC LIMIT 1
          """,
              sender));
      measurements.add(measurement);
      HistoryFixture.assertConsistent(jdbc, sender);
      HistoryFixture.assertConsistent(jdbc, recipient);
      report.put("measurements", measurements);
      Files.createDirectories(Path.of("target"));
      json.writerWithDefaultPrettyPrinter()
          .writeValue(Path.of("target/ledger-history-v" + version + ".json").toFile(), report);
      System.out.println("History measurement V" + version + " " + size + " entries: " + timings);
    }
    assertThat(measurements).hasSize(4);
  }

  private Map<String, Double> cycle(UUID sender, UUID recipient, boolean creditOnly) {
    var results = new LinkedHashMap<String, Double>();
    var credit =
        timed(
            results,
            "credit",
            () ->
                wallets.credit(
                    sender,
                    10,
                    "measure",
                    "history",
                    "history-measure",
                    UUID.randomUUID().toString()));
    if (!creditOnly) {
      timed(
          results,
          "debit",
          () ->
              wallets.debit(
                  sender,
                  1,
                  "measure",
                  "history",
                  "history-measure",
                  UUID.randomUUID().toString()));
      timed(
          results,
          "transfer",
          () ->
              wallets.transfer(
                  sender,
                  recipient,
                  1,
                  "measure",
                  "history",
                  "history-measure",
                  UUID.randomUUID().toString()));
      timed(
          results,
          "refund",
          () ->
              wallets.refund(
                  (UUID) credit.get("transactionId"),
                  "measure",
                  "history",
                  "history-measure",
                  UUID.randomUUID().toString()));
    }
    return results;
  }

  private Map<String, Object> timed(
      Map<String, Double> results, String operation, Supplier<Map<String, Object>> action) {
    long start = System.nanoTime();
    Map<String, Object> receipt = action.get();
    results.put(operation, (System.nanoTime() - start) / 1e6);
    return receipt;
  }

  private Object plan(String statement, Object... arguments) throws Exception {
    List<String> plan =
        jdbc.query(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement,
            (rs, row) -> rs.getString(1),
            arguments);
    return json.readTree(plan.getFirst());
  }
}
