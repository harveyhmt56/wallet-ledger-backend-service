package com.example.walletledger.messaging;

import static org.assertj.core.api.Assertions.*;

import com.example.walletledger.messaging.kafka.BalanceProjection;
import com.example.walletledger.messaging.outbox.OutboxRelay;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;

class MessagingIT extends PostgresIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired JdbcTemplate sql;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ObjectMapper json;
  @Autowired BalanceProjection projection;
  @Autowired com.example.walletledger.wallet.application.WalletService wallets;

  @Test
  void consumerCommitsDeduplicationAndUsesNewestAbsoluteBalance() throws Exception {
    UUID wallet = UUID.randomUUID();
    String newer = event(wallet, 5, 80);
    projection.accept(newer);
    projection.accept(newer);
    projection.accept(event(wallet, 3, 20));
    assertThat(
            sql.queryForObject(
                "select balance from wallet_projection where wallet_id=?", Long.class, wallet))
        .isEqualTo(80);
    assertThat(
            sql.queryForObject(
                "select wallet_sequence from wallet_projection where wallet_id=?",
                Long.class,
                wallet))
        .isEqualTo(5);
    assertThat(
            sql.queryForObject(
                "select count(*) from consumed_event where wallet_id=?", Long.class, wallet))
        .isEqualTo(2);
  }

  @Test
  void expiredLeaseAfterCrashIsPublishedAndAcknowledgedByRealKafka() throws Exception {
    try (var broker = new KafkaContainer("apache/kafka:3.9.1")) {
      broker.start();
      var factory =
          new DefaultKafkaProducerFactory<String, String>(
              Map.of(
                  ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                  broker.getBootstrapServers(),
                  ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                  StringSerializer.class,
                  ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                  StringSerializer.class,
                  ProducerConfig.ACKS_CONFIG,
                  "all"));
      try (var consumer =
          new KafkaConsumer<String, String>(
              Map.of(
                  ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                  broker.getBootstrapServers(),
                  ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                  StringDeserializer.class,
                  ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                  StringDeserializer.class,
                  ConsumerConfig.GROUP_ID_CONFIG,
                  UUID.randomUUID().toString(),
                  ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                  "earliest"))) {
        UUID wallet = UUID.randomUUID();
        wallets.provision(wallet);
        wallets.credit(wallet, 10, "test", "test", "messaging-test", UUID.randomUUID().toString());
        UUID id =
            sql.queryForObject(
                "select event_id from outbox_event where wallet_id=?", UUID.class, wallet);
        String payload =
            sql.queryForObject(
                "select payload::text from outbox_event where event_id=?", String.class, id);
        sql.update(
            "update outbox_event set lease_until=now()-interval '1 second',lease_token=? where event_id=?",
            UUID.randomUUID(),
            id);
        var relay =
            new OutboxRelay(
                jdbc, transactions, new KafkaTemplate<>(factory), new SimpleMeterRegistry());
        assertThat(relay.relayBatch()).isGreaterThanOrEqualTo(1);
        for (int i = 0;
            i < 100
                && !sql.queryForObject(
                    "select delivered_at is not null from outbox_event where event_id=?",
                    Boolean.class,
                    id);
            i++) relay.relayBatch();
        assertThat(
                sql.queryForObject(
                    "select delivered_at is not null from outbox_event where event_id=?",
                    Boolean.class,
                    id))
            .isTrue();
        consumer.subscribe(List.of("wallet.balance-changed.v1"));
        var received = new ArrayList<String>();
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && received.stream().noneMatch(payload::equals)) {
          consumer.poll(Duration.ofMillis(500)).forEach(r -> received.add(r.value()));
        }
        assertThat(received).contains(payload);
        // Drain events created by other acceptance examples before isolating the outage.
        for (int i = 0;
            i < 100
                && sql.queryForObject(
                        "select count(*) from outbox_event where delivered_at is null", Long.class)
                    > 0;
            i++) relay.relayBatch();
        // Broker outage never holds a money transaction open or loses its outbox record.
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        UUID pending;
        try {
          wallets.credit(
              wallet, 1, "test", "during outage", "messaging-test", UUID.randomUUID().toString());
          pending =
              sql.queryForObject(
                  "select event_id from outbox_event where wallet_id=? and wallet_sequence=2",
                  UUID.class,
                  wallet);
          relay.relayBatch();
          assertThat(
                  sql.queryForObject(
                      "select delivered_at is null and attempts>0 from outbox_event where event_id=?",
                      Boolean.class,
                      pending))
              .isTrue();
          assertThat(wallets.balance(wallet)).containsEntry("balance", 11L);
        } finally {
          broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
        }
        sql.update(
            "update outbox_event set lease_until=now()-interval '1 second' where event_id=?",
            pending);
        relay.relayBatch();
        assertThat(
                sql.queryForObject(
                    "select delivered_at is not null from outbox_event where event_id=?",
                    Boolean.class,
                    pending))
            .isTrue();
        String duplicate =
            sql.queryForObject(
                "select payload::text from outbox_event where event_id=?", String.class, pending);
        // Broker acknowledgement followed by a relay crash before marking delivery causes a
        // duplicate.
        sql.update(
            "update outbox_event set delivered_at=null,lease_until=now()-interval '1 second' where event_id=?",
            pending);
        relay.relayBatch();
        until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until
            && received.stream().filter(duplicate::equals).count() < 2) {
          consumer.poll(Duration.ofMillis(500)).forEach(r -> received.add(r.value()));
        }
        assertThat(received.stream().filter(duplicate::equals).count()).isGreaterThanOrEqualTo(2);
        projection.accept(payload);
        for (String delivered : received)
          if (delivered.equals(duplicate)) projection.accept(delivered);
        assertThat(
                sql.queryForObject(
                    "select balance from wallet_projection where wallet_id=?", Long.class, wallet))
            .isEqualTo(11);
        assertThat(
                sql.queryForObject(
                    "select count(*) from consumed_event where wallet_id=?", Long.class, wallet))
            .isEqualTo(2);
      } finally {
        factory.destroy();
      }
    }
  }

  String event(UUID wallet, long sequence, long balance) throws Exception {
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
            "2026-09-07T00:00:00Z",
            "schemaVersion",
            1));
  }
}
