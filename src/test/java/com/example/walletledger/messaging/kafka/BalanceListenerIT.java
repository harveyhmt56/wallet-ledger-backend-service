package com.example.walletledger.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.walletledger.messaging.outbox.OutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@ActiveProfiles("local")
class BalanceListenerIT {
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql");
  private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
  private static final String GROUP = "projection-test-" + UUID.randomUUID();

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "wallet_app");
    registry.add("spring.datasource.password", () -> "wallet_app_local");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "wallet_migration");
    registry.add("spring.flyway.password", () -> "wallet_migration_local");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    registry.add("spring.kafka.admin.auto-create", () -> false);
    registry.add("spring.kafka.listener.auto-startup", () -> false);
    registry.add("ledger.outbox.enabled", () -> false);
    registry.add("ledger.consumer.enabled", () -> true);
    registry.add("ledger.rate-limit.enabled", () -> false);
    registry.add("management.health.redis.enabled", () -> false);
  }

  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper json;

  @Test
  void listenerPersistsFirstDeliveryAndCommitsOffsetsForDuplicatesAndOlderSnapshots()
      throws Exception {
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(OutboxRelay.TOPIC)) {
        admin
            .createTopics(java.util.List.of(new NewTopic(OutboxRelay.TOPIC, 3, (short) 1)))
            .all()
            .get(10, TimeUnit.SECONDS);
      }
      assertThat(listeners.getListenerContainers()).hasSize(1);
      var listener = listeners.getListenerContainers().iterator().next();
      listener.start();
      try {
        await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
        UUID wallet = UUID.randomUUID();
        String first = event(wallet, 1, 50);
        kafka.send(OutboxRelay.TOPIC, wallet.toString(), first).get(5, TimeUnit.SECONDS);
        awaitProjection(wallet, 1, 50, 1);

        kafka.send(OutboxRelay.TOPIC, wallet.toString(), first).get(5, TimeUnit.SECONDS);
        kafka
            .send(OutboxRelay.TOPIC, wallet.toString(), event(wallet, 2, 0))
            .get(5, TimeUnit.SECONDS);
        var last =
            kafka
                .send(OutboxRelay.TOPIC, wallet.toString(), event(wallet, 1, 50))
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
        awaitProjection(wallet, 2, 0, 3);
        TopicPartition partition = new TopicPartition(last.topic(), last.partition());
        await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(
                () -> {
                  var offsets =
                      admin
                          .listConsumerGroupOffsets(GROUP)
                          .partitionsToOffsetAndMetadata()
                          .get(5, TimeUnit.SECONDS);
                  assertThat(offsets).containsKey(partition);
                  assertThat(offsets.get(partition).offset()).isEqualTo(last.offset() + 1);
                });
      } finally {
        listener.stop();
      }
    }
  }

  private void awaitProjection(UUID wallet, long sequence, long balance, long consumed) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      sql.queryForMap(
                          "select wallet_sequence,balance from wallet_projection where wallet_id=?",
                          wallet))
                  .containsEntry("wallet_sequence", sequence)
                  .containsEntry("balance", balance);
              assertThat(
                      sql.queryForObject(
                          "select count(*) from consumed_event where wallet_id=?",
                          Long.class,
                          wallet))
                  .isEqualTo(consumed);
            });
  }

  private String event(UUID wallet, long sequence, long balance) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "walletId",
            wallet,
            "walletSequence",
            sequence,
            "journalTransactionId",
            UUID.randomUUID(),
            "delta",
            10,
            "balanceAfter",
            balance,
            "reason",
            "test",
            "occurredAt",
            "2026-09-13T00:00:00Z",
            "schemaVersion",
            1));
  }
}
