package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.WalletLedgerApplication;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwoInstanceHttpIT extends PostgresIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;

  @Test
  void twoInstancesShareDeduplicationAndLostResponseCanBeRecovered() throws Exception {
    try (var second =
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
                    "--logging.level.root=WARN");
        var client = HttpClient.newHttpClient();
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      int other = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
      UUID player = UUID.randomUUID();
      assertThat(
              send(
                      client,
                      port,
                      "/v1/players",
                      UUID.randomUUID().toString(),
                      "{\"playerId\":\"" + player + "\"}")
                  .statusCode())
          .isEqualTo(200);
      String key = UUID.randomUUID().toString();
      String path = "/v1/wallets/" + player + "/credits";
      String body =
          "{\"amount\":10,\"reason\":\"two instances\",\"source\":\"http-race\",\"reference\":\""
              + key
              + "\"}";
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 20; i++) {
        int target = i % 2 == 0 ? port : other;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return send(client, target, path, key, body);
                }));
      }
      start.countDown();
      var receipts = new HashSet<String>();
      for (var future : futures) {
        var response = future.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        receipts.add(response.body());
      }
      assertThat(receipts).hasSize(1);
      assertThat(json.readTree(receipts.iterator().next()).path("balanceAfter").asLong())
          .isEqualTo(10);
      // Discarding the first committed HTTP receipt models a client losing that response.
      assertThat(send(client, other, path, key, body).body()).isEqualTo(receipts.iterator().next());
    }
  }

  HttpResponse<String> send(HttpClient client, int port, String path, String key, String body)
      throws Exception {
    String auth =
        Base64.getEncoder()
            .encodeToString("service:service-password".getBytes(StandardCharsets.UTF_8));
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Basic " + auth)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
