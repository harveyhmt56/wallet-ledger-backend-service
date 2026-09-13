package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.WalletLedgerApplication;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP requests reach two independent application contexts sharing disposable PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoneyPressureHttpIT extends PostgresIntegrationTest {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final String AUTH =
      "Basic "
          + Base64.getEncoder()
              .encodeToString("service:service-password".getBytes(StandardCharsets.UTF_8));

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  private ConfigurableApplicationContext second;
  private HttpClient client;
  private int otherPort;

  @BeforeAll
  void startSecondInstance() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    second =
        new SpringApplicationBuilder(WalletLedgerApplication.class)
            .run(
                "--spring.profiles.active=local",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=wallet_app",
                "--spring.datasource.password=wallet_app_local",
                "--spring.flyway.enabled=false",
                "--ledger.outbox.enabled=false",
                "--ledger.consumer.enabled=false",
                "--ledger.rate-limit.enabled=false",
                "--spring.kafka.admin.auto-create=false",
                "--spring.kafka.listener.auto-startup=false",
                "--management.health.redis.enabled=false",
                "--spring.lifecycle.timeout-per-shutdown-phase=5s",
                "--logging.level.root=WARN");
    otherPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
    assertThat(otherPort).isNotEqualTo(port);
  }

  @AfterAll
  void stopSecondInstance() {
    if (client != null) client.shutdownNow();
    if (second != null) second.close();
  }

  @Test
  void oneHundredConcurrentDebitsSpendExactlyTheAvailableFundsAndReplayEveryOutcome()
      throws Exception {
    UUID player = provision();
    Outcome funding = post(command(player, "CREDIT", 500, "admin opening balance"), port);
    List<Command> requests =
        IntStream.range(0, 100)
            .mapToObj(n -> command(player, "DEBIT", 10, "purchase item " + n))
            .toList();

    List<Outcome> outcomes = concurrently(requests);

    assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(50);
    assertThat(outcomes)
        .filteredOn(o -> o.status() != 200)
        .hasSize(50)
        .allSatisfy(
            o -> {
              assertThat(o.status()).isEqualTo(409);
              assertThat(o.body().path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
            });
    List<Outcome> applied = new ArrayList<>(List.of(funding));
    applied.addAll(outcomes.stream().filter(o -> o.status() == 200).toList());
    assertMoneyAndHistory(player, applied, 0);

    // A previously rejected purchase must remain rejected even when it could now succeed.
    applied.add(post(command(player, "CREDIT", 1000, "mission reward after race"), otherPort));
    assertMoneyAndHistory(player, applied, 1000);
    List<String> beforeReplay = persistedMoney(player);
    assertExactReplays(outcomes, concurrently(requests));
    assertThat(persistedMoney(player)).isEqualTo(beforeReplay);
    assertBalance(player, 1000, 52);
  }

  @Test
  void simultaneousDuplicateDebitsApplyOnceAndChangedAmountCannotReuseTheKey() throws Exception {
    UUID player = provision();
    Outcome funding = post(command(player, "CREDIT", 100, "admin opening balance"), port);
    Command purchase = command(player, "DEBIT", 10, "purchase sword");
    List<Command> copies = IntStream.range(0, 100).mapToObj(n -> purchase).toList();

    List<Outcome> outcomes = concurrently(copies);

    assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).isEqualTo(200));
    assertThat(outcomes.stream().map(Outcome::rawBody).distinct()).hasSize(1);
    assertMoneyAndHistory(player, List.of(funding, outcomes.getFirst()), 90);
    List<String> beforeConflict = persistedMoney(player);
    Command changed =
        new Command(player, "DEBIT", 11, purchase.key(), purchase.reference(), purchase.reason());
    for (int target : new int[] {port, otherPort}) {
      Outcome conflict = post(changed, target);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
    assertExactReplays(outcomes, concurrently(copies));
    assertThat(persistedMoney(player)).isEqualTo(beforeConflict);
    assertBalance(player, 90, 2);
  }

  @Test
  void concurrentCreditsAndDebitsMatchAnIndependentSequenceOrderedBalanceOracle() throws Exception {
    UUID player = provision();
    Outcome funding = post(command(player, "CREDIT", 1000, "admin opening balance"), port);
    List<Command> requests =
        IntStream.range(0, 80)
            .mapToObj(
                n ->
                    command(
                        player,
                        n % 2 == 0 ? "CREDIT" : "DEBIT",
                        1 + n % 11,
                        n % 2 == 0 ? "mission reward " + n : "purchase item " + n))
            .toList();
    // Funding exceeds the sum of all debits, so any legal serialization succeeds.
    long expected = 1000 + requests.stream().mapToLong(Command::delta).sum();

    List<Outcome> outcomes = concurrently(requests);

    assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).isEqualTo(200));
    List<Outcome> applied = new ArrayList<>(List.of(funding));
    applied.addAll(outcomes);
    assertMoneyAndHistory(player, applied, expected);
    List<String> beforeReplay = persistedMoney(player);
    assertExactReplays(outcomes, concurrently(requests));
    assertThat(persistedMoney(player)).isEqualTo(beforeReplay);
  }

  private void assertMoneyAndHistory(UUID player, List<Outcome> applied, long expectedBalance)
      throws Exception {
    List<Outcome> ordered =
        applied.stream()
            .sorted(Comparator.comparingLong(o -> o.body().path("walletSequence").asLong()))
            .toList();
    long runningBalance = 0;
    for (int n = 0; n < ordered.size(); n++) {
      Outcome outcome = ordered.get(n);
      Command request = outcome.command();
      JsonNode receipt = outcome.body();
      runningBalance = Math.addExact(runningBalance, request.delta());
      assertThat(outcome.status()).isEqualTo(200);
      assertThat(receipt.path("walletSequence").asLong()).isEqualTo(n + 1L);
      assertThat(receipt.path("balanceAfter").asLong()).isEqualTo(runningBalance).isNotNegative();
      assertThat(receipt.path("amount").asLong()).isEqualTo(request.amount());
      assertThat(receipt.path("operation").asText()).isEqualTo(request.operation());
      assertThat(receipt.path("reason").asText()).isEqualTo(request.reason());
      assertThat(receipt.path("walletId").asText()).isEqualTo(player.toString());
      assertThat(receipt.path("playerId").asText()).isEqualTo(player.toString());
      assertThat(receipt.path("occurredAt").asText()).isNotBlank();
      assertDurablePosting(outcome);
    }
    assertThat(runningBalance).isEqualTo(expectedBalance);
    assertBalance(player, expectedBalance, applied.size());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from journal_transaction where source=?",
                Long.class,
                source(player)))
        .isEqualTo((long) applied.size());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where wallet_id=?", Long.class, player))
        .isEqualTo((long) applied.size());
    assertHistory(player, ordered.reversed());
  }

  private void assertDurablePosting(Outcome outcome) throws Exception {
    Command request = outcome.command();
    JsonNode receipt = outcome.body();
    UUID transaction = UUID.fromString(receipt.path("transactionId").asText());
    assertThat(
            jdbc.queryForMap(
                "select operation,amount,actor,reason,source,reference from journal_transaction where transaction_id=?",
                transaction))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "operation", request.operation(),
                "amount", request.amount(),
                "actor", "service",
                "reason", request.reason(),
                "source", source(request.player()),
                "reference", request.reference()));
    assertThat(
            jdbc.queryForMap(
                "select count(*) as entries,sum(amount)::bigint as total from ledger_entry where transaction_id=?",
                transaction))
        .containsEntry("entries", 2L)
        .containsEntry("total", 0L);
    assertThat(
            jdbc.queryForMap(
                "select amount,balance_after,wallet_sequence from ledger_entry where transaction_id=? and wallet_id=?",
                transaction,
                request.player()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "amount", request.delta(),
                "balance_after", receipt.path("balanceAfter").asLong(),
                "wallet_sequence", receipt.path("walletSequence").asLong()));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where journal_transaction_id=? and wallet_id=?",
                Long.class,
                transaction,
                request.player()))
        .as("one durable event for each balance change")
        .isEqualTo(1);
    Map<String, Object> eventRow =
        jdbc.queryForMap(
            "select event_id,wallet_sequence,payload::text from outbox_event where journal_transaction_id=? and wallet_id=?",
            transaction,
            request.player());
    JsonNode event = json.readTree((String) eventRow.get("payload"));
    assertThat(event.path("eventId").asText()).isEqualTo(eventRow.get("event_id").toString());
    assertThat(event.path("journalTransactionId").asText()).isEqualTo(transaction.toString());
    assertThat(event.path("walletId").asText()).isEqualTo(request.player().toString());
    assertThat(event.path("delta").asLong()).isEqualTo(request.delta());
    assertThat(event.path("balanceAfter")).isEqualTo(receipt.path("balanceAfter"));
    assertThat(event.path("walletSequence")).isEqualTo(receipt.path("walletSequence"));
    assertThat(eventRow.get("wallet_sequence")).isEqualTo(receipt.path("walletSequence").asLong());
    assertThat(event.path("reason").asText()).isEqualTo(request.reason());
    assertThat(event.path("occurredAt")).isEqualTo(receipt.path("occurredAt"));
  }

  private void assertHistory(UUID player, List<Outcome> newestFirst) throws Exception {
    int offset = 0;
    Long cursor = null;
    // The page bound turns a stuck/repeated cursor into a test failure instead of an endless loop.
    for (int pageNumber = 0; pageNumber <= newestFirst.size() / 7; pageNumber++) {
      JsonNode page =
          get(
              pageNumber % 2 == 0 ? port : otherPort,
              "/v1/wallets/"
                  + player
                  + "/transactions?limit=7"
                  + (cursor == null ? "" : "&cursor=" + cursor));
      int expectedSize = Math.min(7, newestFirst.size() - offset);
      assertThat(page.path("items")).hasSize(expectedSize);
      for (JsonNode item : page.path("items")) {
        Outcome expected = newestFirst.get(offset++);
        assertThat(item.path("transactionId")).isEqualTo(expected.body().path("transactionId"));
        assertThat(item.path("walletSequence")).isEqualTo(expected.body().path("walletSequence"));
        assertThat(item.path("balanceAfter")).isEqualTo(expected.body().path("balanceAfter"));
        assertThat(item.path("delta").asLong()).isEqualTo(expected.command().delta());
        assertThat(item.path("operation").asText()).isEqualTo(expected.command().operation());
        assertThat(item.path("reason").asText()).isEqualTo(expected.command().reason());
        assertThat(item.path("reference").asText()).isEqualTo(expected.command().reference());
        assertThat(item.path("source").asText()).isEqualTo(source(player));
        assertThat(item.path("actor").asText()).isEqualTo("service");
      }
      if (offset == newestFirst.size()) {
        assertThat(page.path("nextCursor").isNull()).isTrue();
        return;
      }
      cursor = newestFirst.get(offset - 1).body().path("walletSequence").asLong();
      assertThat(page.path("nextCursor").asLong()).isEqualTo(cursor);
    }
    throw new AssertionError("History did not expose every committed transaction");
  }

  private void assertBalance(UUID player, long balance, long sequence) throws Exception {
    for (int target : new int[] {port, otherPort}) {
      JsonNode current = get(target, "/v1/wallets/" + player + "/balance");
      assertThat(current.path("balance").asLong()).isEqualTo(balance);
      assertThat(current.path("sequence").asLong()).isEqualTo(sequence);
    }
  }

  private List<String> persistedMoney(UUID player) {
    return jdbc.queryForList(
        """
        select 'wallet:' || to_jsonb(w)::text as row from wallet w where wallet_id=?
        union all
        select 'journal:' || to_jsonb(j)::text from journal_transaction j where source=?
        union all
        select 'entry:' || to_jsonb(e)::text from ledger_entry e
          join journal_transaction j using(transaction_id) where j.source=?
        union all
        select 'outbox:' || to_jsonb(o)::text from outbox_event o where wallet_id=?
        order by 1
        """,
        String.class,
        player,
        source(player),
        source(player),
        player);
  }

  private void assertExactReplays(List<Outcome> original, List<Outcome> replays) {
    assertThat(replays).hasSize(original.size());
    for (int n = 0; n < original.size(); n++) {
      assertThat(replays.get(n).command()).isEqualTo(original.get(n).command());
      assertThat(replays.get(n).status()).isEqualTo(original.get(n).status());
      assertThat(replays.get(n).rawBody()).isEqualTo(original.get(n).rawBody());
    }
  }

  private List<Outcome> concurrently(List<Command> requests) throws Exception {
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    var ready = new CountDownLatch(requests.size());
    var start = new CountDownLatch(1);
    List<Future<Outcome>> futures = new ArrayList<>();
    try {
      for (int n = 0; n < requests.size(); n++) {
        Command request = requests.get(n);
        int target = n % 2 == 0 ? port : otherPort;
        futures.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(15, TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent request start was not released");
                  return post(request, target);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).as("all HTTP workers ready").isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      start.countDown();
      List<Outcome> results = new ArrayList<>();
      for (Future<Outcome> future : futures) {
        results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return results;
    } finally {
      start.countDown();
      futures.forEach(future -> future.cancel(true));
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).as("HTTP workers stopped").isTrue();
    }
  }

  private UUID provision() throws Exception {
    UUID player = UUID.randomUUID();
    HttpResponse<String> result =
        send(
            port,
            "/v1/players",
            UUID.randomUUID().toString(),
            json.writeValueAsString(Map.of("playerId", player)));
    assertThat(result.statusCode()).isEqualTo(200);
    return player;
  }

  private Outcome post(Command request, int target) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "amount", request.amount(),
                "reason", request.reason(),
                "source", source(request.player()),
                "reference", request.reference()));
    HttpResponse<String> response =
        send(
            target,
            "/v1/wallets/"
                + request.player()
                + (request.operation().equals("CREDIT") ? "/credits" : "/debits"),
            request.key(),
            body);
    return new Outcome(
        request, response.statusCode(), response.body(), json.readTree(response.body()));
  }

  private HttpResponse<String> send(int target, String path, String key, String body)
      throws Exception {
    return client.send(
        request(target, path)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode get(int target, String path) throws Exception {
    HttpResponse<String> response =
        client.send(request(target, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private HttpRequest.Builder request(int target, String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + target + path))
        .timeout(REQUEST_TIMEOUT)
        .header("Authorization", AUTH);
  }

  private Command command(UUID player, String operation, long amount, String reason) {
    return new Command(
        player,
        operation,
        amount,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        reason);
  }

  private String source(UUID player) {
    return "money-pressure-http:" + player;
  }

  private record Command(
      UUID player, String operation, long amount, String key, String reference, String reason) {
    long delta() {
      return operation.equals("CREDIT") ? amount : -amount;
    }
  }

  private record Outcome(Command command, int status, String rawBody, JsonNode body) {}
}
