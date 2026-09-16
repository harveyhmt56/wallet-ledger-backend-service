package com.example.walletledger.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.walletledger.messaging.outbox.OutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
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
  @Autowired DefaultErrorHandler errors;
  @Autowired com.example.walletledger.wallet.application.WalletService wallets;

  @Test
  void auditedReplayUsesAuthoritativeOutboxSnapshotAndLeavesMoneyAndEvidenceUnchanged()
      throws Exception {
    UUID wallet = UUID.randomUUID();
    wallets.provision(wallet);
    wallets.credit(
        wallet, 35, "operator-test", "credit", "f02-replay", UUID.randomUUID().toString());
    var source =
        sql.queryForMap(
            "select event_id,payload::text from outbox_event where wallet_id=?", wallet);
    var listener = listeners.getListenerContainers().iterator().next();
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      ensureTopic(admin);
      listener.start();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
      var poison =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), "{corrupted-in-transit")
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, poison);
      assertThat(quarantined(poison)).isEqualTo(1);
      UUID attempt = UUID.randomUUID();
      JdbcTemplate operator = migrationJdbc();
      assertThat(
              operator.update(
                  """
          insert into kafka_quarantine_replay_attempt
            (attempt_id, consumer_group, topic, partition_id, record_offset, source_event_id, approved_payload, reason)
          select ?, q.consumer_group, q.topic, q.partition_id, q.record_offset, o.event_id, o.payload, ?
          from kafka_quarantine q cross join outbox_event o
          where q.consumer_group=? and q.topic=? and q.partition_id=? and q.record_offset=?
            and o.event_id=?
            and o.payload->>'eventId'=o.event_id::text
            and o.payload->>'walletId'=o.wallet_id::text
            and o.payload->>'walletSequence'=o.wallet_sequence::text
            and o.payload->>'journalTransactionId'=o.journal_transaction_id::text
          """,
                  attempt,
                  "F02 verified source replay",
                  GROUP,
                  poison.topic(),
                  poison.partition(),
                  poison.offset(),
                  source.get("event_id")))
          .isEqualTo(1);
      var audit =
          operator.queryForMap(
              "select approved_payload::text,requested_by,requested_at from kafka_quarantine_replay_attempt where attempt_id=?",
              attempt);
      assertThat(audit)
          .containsEntry("approved_payload", source.get("payload"))
          .containsEntry("requested_by", "wallet_migration");
      assertThat(audit.get("requested_at")).isNotNull();
      String line =
          operator.queryForObject(
              "select (approved_payload->>'walletId') || chr(9) || approved_payload::text from kafka_quarantine_replay_attempt where attempt_id=?",
              String.class,
              attempt);
      assertThat(line).startsWith(wallet + "\t").doesNotContain("\n");
      String[] parts = line.split("\t", 2);
      kafka.send(OutboxRelay.TOPIC, 0, parts[0], parts[1]).get(5, TimeUnit.SECONDS);
      var duplicate =
          kafka
              .send(OutboxRelay.TOPIC, 0, parts[0], parts[1])
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, duplicate);
      awaitProjection(wallet, 1, 35, 1);
      assertThat(
              sql.queryForObject(
                  "select event_id from consumed_event where wallet_id=?", UUID.class, wallet))
          .isEqualTo(source.get("event_id"));
      assertThat(wallets.balance(wallet))
          .containsEntry("balance", 35L)
          .containsEntry("sequence", 1L);
      assertThat(
              sql.queryForObject(
                  "select count(*) from outbox_event where wallet_id=?", Long.class, wallet))
          .isEqualTo(1);
      assertThat(
              sql.queryForObject(
                  "select payload from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
                  byte[].class,
                  GROUP,
                  poison.topic(),
                  poison.partition(),
                  poison.offset()))
          .isEqualTo("{corrupted-in-transit".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(
              () ->
                  sql.update(
                      "insert into kafka_quarantine_replay_attempt select * from kafka_quarantine_replay_attempt where attempt_id=?",
                      attempt))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .rootCause()
          .isInstanceOf(java.sql.SQLException.class)
          .extracting(failure -> ((java.sql.SQLException) failure).getSQLState())
          .isEqualTo("42501");
    } finally {
      listener.stop();
    }
  }

  @Test
  void poisonRecordsAreDurablyQuarantinedAndLaterRecordsProgressOnBothPartitions()
      throws Exception {
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      ensureTopic(admin);
      var listener = listeners.getListenerContainers().iterator().next();
      var attempts = new java.util.concurrent.ConcurrentHashMap<Long, Integer>();
      errors.setRetryListeners(
          (record, exception, attempt) -> attempts.merge(record.offset(), 1, Integer::sum));
      listener.start();
      try {
        await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
        UUID wallet = UUID.randomUUID();
        String key = "poison-\u0000-" + wallet;
        List<String> payloads =
            Arrays.asList(
                "{invalid-json",
                event(wallet, 1, 10).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                "\u0000",
                null);
        List<RecordMetadata> poisons = new ArrayList<>();
        for (String payload : payloads) {
          poisons.add(
              kafka
                  .send(OutboxRelay.TOPIC, 0, key, payload)
                  .get(5, TimeUnit.SECONDS)
                  .getRecordMetadata());
        }
        var same =
            kafka
                .send(OutboxRelay.TOPIC, 0, wallet.toString(), event(wallet, 2, 20))
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
        UUID otherWallet = UUID.randomUUID();
        var other =
            kafka
                .send(OutboxRelay.TOPIC, 1, otherWallet.toString(), event(otherWallet, 1, 30))
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
        awaitProjection(wallet, 2, 20, 1);
        awaitProjection(otherWallet, 1, 30, 1);
        awaitOffset(admin, same);
        awaitOffset(admin, other);
        for (int i = 0; i < poisons.size(); i++) {
          var poison = poisons.get(i);
          var row =
              sql.queryForMap(
                  "select * from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
                  GROUP,
                  poison.topic(),
                  poison.partition(),
                  poison.offset());
          assertThat((byte[]) row.get("record_key"))
              .isEqualTo(key.getBytes(StandardCharsets.UTF_8));
          assertThat((byte[]) row.get("payload"))
              .isEqualTo(
                  payloads.get(i) == null
                      ? null
                      : payloads.get(i).getBytes(StandardCharsets.UTF_8));
          assertThat(row.get("error_type")).isNotNull();
          assertThat(row.get("quarantined_at")).isNotNull();
          assertThat(attempts.get(poison.offset())).isEqualTo(1);
        }
      } finally {
        listener.stop();
        errors.setRetryListeners();
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "schemaVersion, 4294967297",
    "walletSequence, 27670116110564327423",
    "balanceAfter, 18446744073709551616"
  })
  void oversizedEventIntegersAreQuarantinedBeforeWritesAndFollowingSnapshotStillApplies(
      String field, String value) throws Exception {
    UUID wallet = UUID.randomUUID();
    wallets.provision(wallet);
    wallets.credit(wallet, 45, "parser-test", "first", "f03", UUID.randomUUID().toString());
    wallets.credit(wallet, 10, "parser-test", "second", "f03", UUID.randomUUID().toString());
    var snapshots =
        sql.queryForList(
            "select payload::text from outbox_event where wallet_id=? order by wallet_sequence",
            String.class,
            wallet);
    var ledger =
        sql.queryForList(
            "select * from ledger_entry where wallet_id=? order by wallet_sequence", wallet);
    ObjectNode invalid = (ObjectNode) json.readTree(snapshots.get(1));
    UUID invalidId = UUID.fromString(invalid.path("eventId").asText());
    invalid.put(field, new BigInteger(value));
    String poisonPayload = json.writeValueAsString(invalid);
    var attempts = new java.util.concurrent.ConcurrentHashMap<Long, Integer>();
    errors.setRetryListeners(
        (record, failure, attempt) -> attempts.merge(record.offset(), 1, Integer::sum));
    var listener = listeners.getListenerContainers().iterator().next();
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      ensureTopic(admin);
      listener.start();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
      var first =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), snapshots.get(0))
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, first);
      awaitProjection(wallet, 1, 45, 1);

      var poison =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), poisonPayload)
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, poison);
      assertThat(quarantined(poison)).isEqualTo(1);
      var quarantine =
          sql.queryForMap(
              "select * from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
              GROUP,
              poison.topic(),
              poison.partition(),
              poison.offset());
      assertThat((byte[]) quarantine.get("record_key"))
          .isEqualTo(wallet.toString().getBytes(StandardCharsets.UTF_8));
      assertThat((byte[]) quarantine.get("payload"))
          .isEqualTo(poisonPayload.getBytes(StandardCharsets.UTF_8));
      assertThat(quarantine.get("error_type")).isEqualTo(IllegalArgumentException.class.getName());
      assertThat(quarantine.get("quarantined_at")).isNotNull();
      assertThat(attempts.get(poison.offset())).isEqualTo(1);
      awaitProjection(wallet, 1, 45, 1);
      assertThat(
              sql.queryForObject(
                  "select count(*) from consumed_event where event_id=?", Long.class, invalidId))
          .isZero();

      // The original snapshot has the same event ID and must remain eligible after rejection.
      var following =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), snapshots.get(1))
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, following);
      awaitProjection(wallet, 2, 55, 2);
      assertThat(quarantined(following)).isZero();
      assertThat(wallets.balance(wallet))
          .containsEntry("balance", 55L)
          .containsEntry("sequence", 2L);
      assertThat(
              sql.queryForList(
                  "select * from ledger_entry where wallet_id=? order by wallet_sequence", wallet))
          .isEqualTo(ledger);
      assertThat(
              sql.queryForList(
                  "select payload::text from outbox_event where wallet_id=? order by wallet_sequence",
                  String.class,
                  wallet))
          .isEqualTo(snapshots);
    } finally {
      listener.stop();
      errors.setRetryListeners();
    }
  }

  private void ensureTopic(AdminClient admin) throws Exception {
    if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(OutboxRelay.TOPIC)) {
      admin
          .createTopics(List.of(new NewTopic(OutboxRelay.TOPIC, 3, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void quarantineCommitFailureRetainsOffsetAndRestartDeduplicatesRecovery() throws Exception {
    JdbcTemplate migration = migrationJdbc();
    migration.execute(
        """
        create function f02_reject_quarantine() returns trigger language plpgsql as $$
        begin raise exception 'injected quarantine commit failure'; end $$
        """);
    migration.execute(
        """
        create constraint trigger f02_reject_quarantine after insert on kafka_quarantine
        deferrable initially deferred for each row execute function f02_reject_quarantine()
        """);
    var failedRecoveries = new java.util.concurrent.atomic.AtomicInteger();
    errors.setRetryListeners(
        new org.springframework.kafka.listener.RetryListener() {
          @Override
          public void failedDelivery(
              org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
              Exception failure,
              int attempt) {}

          @Override
          public void recoveryFailed(
              org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
              Exception original,
              Exception failure) {
            failedRecoveries.incrementAndGet();
          }
        });
    var listener = listeners.getListenerContainers().iterator().next();
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      ensureTopic(admin);
      listener.start();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
      UUID wallet = UUID.randomUUID();
      var poison =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), "{commit-failure")
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      var valid =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), event(wallet, 1, 40))
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(failedRecoveries.get()).isGreaterThanOrEqualTo(2));
      assertOffsetNotPast(admin, poison);
      assertThat(quarantined(poison)).isZero();
      assertThat(
              sql.queryForObject(
                  "select count(*) from consumed_event where wallet_id=?", Long.class, wallet))
          .isZero();
      assertThat(
              sql.queryForObject(
                  "select count(*) from wallet_projection where wallet_id=?", Long.class, wallet))
          .isZero();
      migration.execute("drop trigger f02_reject_quarantine on kafka_quarantine");
      awaitProjection(wallet, 1, 40, 1);
      awaitOffset(admin, valid);
      assertThat(quarantined(poison)).isEqualTo(1);
      var original =
          sql.queryForMap(
              "select * from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
              GROUP,
              poison.topic(),
              poison.partition(),
              poison.offset());
      listener.stop();
      TopicPartition partition = new TopicPartition(poison.topic(), poison.partition());
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          admin
                              .describeConsumerGroups(List.of(GROUP))
                              .all()
                              .get(5, TimeUnit.SECONDS)
                              .get(GROUP)
                              .members())
                      .isEmpty());
      // Reproduce redelivery after the DB committed but the source offset was not retained.
      admin
          .alterConsumerGroupOffsets(
              GROUP,
              Map.of(
                  partition,
                  new org.apache.kafka.clients.consumer.OffsetAndMetadata(poison.offset())))
          .all()
          .get(5, TimeUnit.SECONDS);
      listener.start();
      awaitOffset(admin, valid);
      awaitProjection(wallet, 1, 40, 1);
      assertThat(quarantined(poison)).isEqualTo(1);
      assertThat(
              sql.queryForMap(
                  "select * from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
                  GROUP,
                  poison.topic(),
                  poison.partition(),
                  poison.offset()))
          .usingRecursiveComparison()
          .isEqualTo(original);
      assertThatThrownBy(
              () -> sql.update("delete from kafka_quarantine where consumer_group=?", GROUP))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .rootCause()
          .isInstanceOf(java.sql.SQLException.class)
          .extracting(failure -> ((java.sql.SQLException) failure).getSQLState())
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  sql.update(
                      "update kafka_quarantine set error_type='changed' where consumer_group=?",
                      GROUP))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .rootCause()
          .isInstanceOf(java.sql.SQLException.class)
          .extracting(failure -> ((java.sql.SQLException) failure).getSQLState())
          .isEqualTo("42501");
    } finally {
      listener.stop();
      errors.setRetryListeners();
      migration.execute("drop trigger if exists f02_reject_quarantine on kafka_quarantine");
      migration.execute("drop function f02_reject_quarantine()");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void databaseFailureRetriesThenEitherAppliesOrQuarantinesForReplay(boolean repairBeforeExhaustion)
      throws Exception {
    JdbcTemplate migration = migrationJdbc();
    migration.execute(
        """
        create function f02_reject_projection() returns trigger language plpgsql as $$
        begin raise exception 'injected transient projection failure' using errcode='40001'; end $$
        """);
    migration.execute(
        "create trigger f02_reject_projection before insert on consumed_event for each row execute function f02_reject_projection()");
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    errors.setRetryListeners(
        (record, failure, attempt) -> {
          attempts.incrementAndGet();
          if (repairBeforeExhaustion && attempt == 2)
            migration.execute("drop trigger f02_reject_projection on consumed_event");
        });
    var listener = listeners.getListenerContainers().iterator().next();
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      ensureTopic(admin);
      listener.start();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(3));
      UUID wallet = UUID.randomUUID();
      String payload = event(wallet, 1, 60);
      var failed =
          kafka
              .send(OutboxRelay.TOPIC, 0, wallet.toString(), payload)
              .get(5, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitOffset(admin, failed);
      if (repairBeforeExhaustion) {
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(quarantined(failed)).isZero();
      } else {
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(quarantined(failed)).isEqualTo(1);
        assertThat(
                sql.queryForObject(
                    "select count(*) from consumed_event where wallet_id=?", Long.class, wallet))
            .isZero();
        assertThat(
                sql.queryForObject(
                    "select count(*) from wallet_projection where wallet_id=?", Long.class, wallet))
            .isZero();
        migration.execute("drop trigger f02_reject_projection on consumed_event");
        // Repeat the exact event ID; the rejected transaction must not have consumed it.
        var replay =
            kafka
                .send(OutboxRelay.TOPIC, 0, wallet.toString(), payload)
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
        awaitOffset(admin, replay);
      }
      awaitProjection(wallet, 1, 60, 1);
    } finally {
      listener.stop();
      errors.setRetryListeners();
      migration.execute("drop trigger if exists f02_reject_projection on consumed_event");
      migration.execute("drop function f02_reject_projection()");
    }
  }

  private JdbcTemplate migrationJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "wallet_migration", "wallet_migration_local"));
  }

  private long quarantined(RecordMetadata record) {
    return sql.queryForObject(
        "select count(*) from kafka_quarantine where consumer_group=? and topic=? and partition_id=? and record_offset=?",
        Long.class,
        GROUP,
        record.topic(),
        record.partition(),
        record.offset());
  }

  private void assertOffsetNotPast(AdminClient admin, RecordMetadata record) throws Exception {
    var offset =
        admin
            .listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata()
            .get(5, TimeUnit.SECONDS)
            .get(new TopicPartition(record.topic(), record.partition()));
    assertThat(offset == null || offset.offset() <= record.offset()).isTrue();
  }

  private void awaitOffset(AdminClient admin, RecordMetadata record) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var offsets =
                  admin
                      .listConsumerGroupOffsets(GROUP)
                      .partitionsToOffsetAndMetadata()
                      .get(5, TimeUnit.SECONDS);
              assertThat(offsets)
                  .containsKey(new TopicPartition(record.topic(), record.partition()));
              assertThat(
                      offsets.get(new TopicPartition(record.topic(), record.partition())).offset())
                  .isEqualTo(record.offset() + 1);
            });
  }

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
                      sql.queryForObject(
                          "select count(*) from wallet_projection where wallet_id=?",
                          Long.class,
                          wallet))
                  .isEqualTo(1);
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
