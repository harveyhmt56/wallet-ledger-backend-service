package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Stops only this test's disposable database; the shared integration database stays available. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("local")
@Testcontainers
class DatabaseOutageHttpIT {
  private static final int DATABASE_PORT = unusedLoopbackPort();

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql")
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", DATABASE_PORT),
                              new ExposedPort(5432))));

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "wallet_app");
    registry.add("spring.datasource.password", () -> "wallet_app_local");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "wallet_migration");
    registry.add("spring.flyway.password", () -> "wallet_migration_local");
    registry.add("ledger.outbox.enabled", () -> false);
    registry.add("ledger.consumer.enabled", () -> false);
    registry.add("ledger.rate-limit.enabled", () -> false);
    registry.add("spring.kafka.admin.auto-create", () -> false);
    registry.add("spring.kafka.listener.auto-startup", () -> false);
    registry.add("management.health.redis.enabled", () -> false);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate sql;

  @Test
  void databaseOutageReturnsRetryableProblemAndSameKeyRecoveryMovesMoneyExactlyOnce()
      throws Exception {
    UUID player = UUID.randomUUID();
    String path = "/v1/wallets/" + player;
    String creditKey = UUID.randomUUID().toString();
    String debitKey = UUID.randomUUID().toString();
    String credit = money(100, creditKey);
    String debit = money(30, debitKey);

    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThat(
              send(
                      client,
                      "/v1/players",
                      UUID.randomUUID().toString(),
                      "{\"playerId\":\"" + player + "\"}")
                  .statusCode())
          .isEqualTo(200);
      var credited = send(client, path + "/credits", creditKey, credit);
      assertThat(credited.statusCode()).isEqualTo(200);
      var beforeOutage = persistedState();
      var bindingsBefore = portBindings();
      assertThat(bindingsBefore).containsExactly("127.0.0.1:" + DATABASE_PORT);

      try {
        POSTGRES
            .getDockerClient()
            .stopContainerCmd(POSTGRES.getContainerId())
            .withTimeout(1)
            .exec();
        assertUnavailable(send(client, path + "/debits", debitKey, debit), path + "/debits");
        // Remove stale pooled connections to exercise fresh acquisition while PostgreSQL is down.
        ((HikariDataSource) sql.getDataSource()).getHikariPoolMXBean().softEvictConnections();
        assertUnavailable(send(client, path + "/debits", debitKey, debit), path + "/debits");
      } finally {
        // Restart the same container to retain both its committed rows and the pool's JDBC URL.
        POSTGRES.getDockerClient().startContainerCmd(POSTGRES.getContainerId()).exec();
        assertThat(portBindings()).isEqualTo(bindingsBefore);
        await()
            .atMost(Duration.ofSeconds(30))
            .ignoreExceptions()
            .untilAsserted(
                () ->
                    assertThat(administratorJdbc().queryForObject("select 1", Integer.class))
                        .isEqualTo(1));
        await()
            .atMost(Duration.ofSeconds(15))
            .ignoreExceptions()
            .untilAsserted(
                () -> assertThat(sql.queryForObject("select 1", Integer.class)).isEqualTo(1));
      }

      assertThat(persistedState())
          .as("The outage committed no partial command")
          .isEqualTo(beforeOutage);
      assertThat(
              sql.queryForObject(
                  "select count(*) from idempotency_request where actor='service' and request_key=?",
                  Long.class,
                  debitKey))
          .isZero();

      var creditReplay = send(client, path + "/credits", creditKey, credit);
      assertThat(creditReplay.statusCode()).isEqualTo(credited.statusCode());
      assertThat(creditReplay.body()).isEqualTo(credited.body());
      assertThat(persistedState())
          .as("Successful commands still replay after restart")
          .isEqualTo(beforeOutage);

      var debited = send(client, path + "/debits", debitKey, debit);
      assertThat(debited.statusCode()).isEqualTo(200);
      assertThat(json.readTree(debited.body()).path("balanceAfter").asLong()).isEqualTo(70);
      assertThat(sql.queryForMap("select balance,sequence from wallet where wallet_id=?", player))
          .containsEntry("balance", 70L)
          .containsEntry("sequence", 2L);
      assertThat(
              sql.queryForObject(
                  "select sum(amount) from ledger_entry where wallet_id=?", Long.class, player))
          .isEqualTo(70);
      assertThat(sql.queryForObject("select count(*) from journal_transaction", Long.class))
          .isEqualTo(2);
      assertThat(sql.queryForObject("select count(*) from ledger_entry", Long.class)).isEqualTo(4);
      assertThat(
              sql.queryForObject(
                  "select count(*) from ledger_entry where wallet_id=?", Long.class, player))
          .isEqualTo(2);
      assertThat(
              sql.queryForObject(
                  "select count(*) from outbox_event where wallet_id=?", Long.class, player))
          .isEqualTo(2);
      assertThat(
              sql.queryForObject(
                  "select status from idempotency_request where actor='service' and request_key=?",
                  Integer.class,
                  debitKey))
          .isEqualTo(200);
      var recovered = persistedState();
      var debitReplay = send(client, path + "/debits", debitKey, debit);
      assertThat(debitReplay.statusCode()).isEqualTo(debited.statusCode());
      assertThat(debitReplay.body()).isEqualTo(debited.body());
      assertThat(persistedState())
          .as("The recovered command is applied exactly once")
          .isEqualTo(recovered);
      assertThat(administratorJdbc().queryForList("select * from public.audit_ledger_integrity()"))
          .isEmpty();
    }
  }

  private void assertUnavailable(HttpResponse<String> unavailable, String path) throws IOException {
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.headers().firstValue("Retry-After")).contains("1");
    assertThat(
            MediaType.parseMediaType(unavailable.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .isTrue();
    var problem = json.readTree(unavailable.body());
    assertThat(problem.path("type").asText()).isEqualTo("about:blank");
    assertThat(problem.path("status").asInt()).isEqualTo(503);
    assertThat(problem.path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(problem.path("detail").asText())
        .isEqualTo("Database operation could not complete; retry with the same idempotency key");
    assertThat(problem.path("instance").asText()).isEqualTo(path);
  }

  private List<String> persistedState() {
    return sql.queryForList(
        """
        select 'wallet:' || to_jsonb(w)::text as row from wallet w
        union all
        select 'journal:' || to_jsonb(j)::text from journal_transaction j
        union all
        select 'entry:' || to_jsonb(e)::text from ledger_entry e
        union all
        select 'idempotency:' || to_jsonb(i)::text from idempotency_request i
        union all
        select 'outbox:' || to_jsonb(o)::text from outbox_event o
        order by 1
        """,
        String.class);
  }

  private static int unusedLoopbackPort() {
    try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Unable to allocate isolated PostgreSQL loopback port", failure);
    }
  }

  private static JdbcTemplate administratorJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private static List<String> portBindings() {
    var bindings =
        POSTGRES
            .getDockerClient()
            .inspectContainerCmd(POSTGRES.getContainerId())
            .exec()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(new ExposedPort(5432));
    return Arrays.stream(bindings)
        .map(binding -> binding.getHostIp() + ":" + binding.getHostPortSpec())
        .toList();
  }

  private String money(long amount, String reference) {
    return "{\"amount\":"
        + amount
        + ",\"reason\":\"Database outage regression\",\"source\":\"outage-test\",\"reference\":\""
        + reference
        + "\"}";
  }

  private HttpResponse<String> send(HttpClient client, String path, String key, String body)
      throws Exception {
    String auth =
        Base64.getEncoder()
            .encodeToString("service:service-password".getBytes(StandardCharsets.UTF_8));
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Basic " + auth)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
