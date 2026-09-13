package com.example.walletledger.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.walletledger.messaging.outbox.OutboxRelay;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

/** Bounded scratch characterization: passing demonstrates a poison-record recovery defect. */
@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class F02PoisonEventCharacterizationIT {
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("wallet_ledger")
          .withUsername("postgres_admin")
          .withPassword("postgres_admin_local")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("docker/postgres/01-roles.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/01-roles.sql");
  private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
  private static final String GROUP = "qa-f02-" + UUID.randomUUID();

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
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
  @Autowired DefaultErrorHandler errors;

  @Test
  void poisonRecordKeepsRetryingAndBlocksLaterSamePartitionEvent() throws Exception {
    try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(OutboxRelay.TOPIC, 3, (short) 1)))
          .all().get(10, TimeUnit.SECONDS);
      assertThat(listeners.getListenerContainers()).hasSize(1);
      var listener = listeners.getListenerContainers().iterator().next();
      AtomicInteger poisonAttempts = new AtomicInteger();
      // Observation only: no changes to classifications, back-off, recovery, or offsets.
      errors.setRetryListeners((record, exception, attempt) -> {
        if ("{qa-invalid-json".equals(record.value())) poisonAttempts.incrementAndGet();
      });
      listener.start();
      try {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(listener.getAssignedPartitions()).hasSize(3));
        UUID baseline0 = UUID.randomUUID();
        UUID baseline1 = UUID.randomUUID();
        kafka.send(OutboxRelay.TOPIC, 0, baseline0.toString(), event(baseline0))
            .get(5, TimeUnit.SECONDS);
        kafka.send(OutboxRelay.TOPIC, 1, baseline1.toString(), event(baseline1))
            .get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
          assertThat(projected(baseline0)).isEqualTo(1);
          assertThat(projected(baseline1)).isEqualTo(1);
        });
        UUID poisonWallet = UUID.randomUUID();
        var poison = kafka.send(OutboxRelay.TOPIC, 0, poisonWallet.toString(), "{qa-invalid-json")
            .get(5, TimeUnit.SECONDS).getRecordMetadata();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(poisonAttempts.get()).isGreaterThanOrEqualTo(2));
        UUID later0 = UUID.randomUUID();
        UUID later1 = UUID.randomUUID();
        var samePartition = kafka.send(OutboxRelay.TOPIC, 0, later0.toString(), event(later0))
            .get(5, TimeUnit.SECONDS).getRecordMetadata();
        var otherPartition = kafka.send(OutboxRelay.TOPIC, 1, later1.toString(), event(later1))
            .get(5, TimeUnit.SECONDS).getRecordMetadata();
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() ->
            assertThat(projected(later0)).as("Observed defect: valid record remains behind poison")
                .isZero());
        assertThat(poisonAttempts.get()).isGreaterThanOrEqualTo(4);
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
            .get(5, TimeUnit.SECONDS);
        System.out.println("F02 expected-defect: group=" + GROUP
            + " assignments=" + listener.getAssignedPartitions()
            + " poison=(partition=" + poison.partition() + ",offset=" + poison.offset() + ")"
            + " retriesObserved=" + poisonAttempts.get()
            + " samePartitionValid=(wallet=" + later0 + ",partition=" + samePartition.partition()
            + ",offset=" + samePartition.offset() + ",projected=" + projected(later0) + ")"
            + " otherPartitionValid=(wallet=" + later1 + ",partition=" + otherPartition.partition()
            + ",offset=" + otherPartition.offset() + ",projected=" + projected(later1) + ")"
            + " committedOffsets=" + offsets);
      } finally {
        listener.stop();
      }
    }
  }

  private long projected(UUID wallet) {
    return sql.queryForObject("select count(*) from wallet_projection where wallet_id=?",
        Long.class, wallet);
  }

  private String event(UUID wallet) {
    return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"walletId\":\"" + wallet
        + "\",\"walletSequence\":1,\"balanceAfter\":10,\"schemaVersion\":1}";
  }
}
