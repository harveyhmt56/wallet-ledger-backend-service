package com.example.walletledger.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class KafkaQuarantineTest {
  private static final String GROUP = "projection-test";
  private static final String TOPIC = "wallet.balance-changed.v1";
  private static final long TIMESTAMP = 1_757_966_400_000L;
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final JdbcClient.StatementSpec insert =
      mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final KafkaQuarantine quarantine =
      new KafkaQuarantine(jdbc, transactions, metrics, GROUP);

  @BeforeEach
  void prepareDatabase() {
    when(transactions.getTransaction(any())).thenReturn(transaction);
    when(jdbc.sql(anyString())).thenReturn(insert);
    when(insert.update()).thenReturn(1);
  }

  @AfterEach
  void closeMetrics() {
    metrics.close();
  }

  @Test
  void originalRecordMetadataAndUtf8BytesAreCommittedBeforeCountingQuarantine() {
    String key = "玩家\u0000key";
    String payload = "{\"broken\":\"金币\u0000\"}";
    var failure =
        new IllegalStateException("Wrapper message", new IllegalArgumentException(payload));
    doAnswer(
            ignored -> {
              assertThat(count("wallet.kafka.quarantined")).isZero();
              return null;
            })
        .when(transactions)
        .commit(transaction);

    quarantine.accept(record(key, payload), failure);

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var ordered = inOrder(insert, transactions);
    ordered.verify(insert).update();
    ordered.verify(transactions).commit(transaction);
    verify(transactions, never()).rollback(any());
    verify(insert).param("group", GROUP);
    verify(insert).param("topic", TOPIC);
    verify(insert).param("partition", 2);
    verify(insert).param("offset", 42L);
    verify(insert).param("timestamp", TIMESTAMP);
    verify(insert).param("key", key.getBytes(StandardCharsets.UTF_8), Types.BINARY);
    verify(insert).param("payload", payload.getBytes(StandardCharsets.UTF_8), Types.BINARY);
    verify(insert).param("error", IllegalArgumentException.class.getName());
    assertThat(count("wallet.kafka.quarantined")).isEqualTo(1);
    assertThat(count("wallet.kafka.quarantine.failures")).isZero();
  }

  @Test
  void nullKafkaKeyAndTombstonePayloadRemainNullInQuarantine() {
    quarantine.accept(record(null, null), new IllegalArgumentException("No payload"));

    verify(insert).param("key", null, Types.BINARY);
    verify(insert).param("payload", null, Types.BINARY);
    verify(transactions).commit(transaction);
    assertThat(count("wallet.kafka.quarantined")).isEqualTo(1);
  }

  @Test
  void repeatedOriginalOffsetCommitsWithoutCountingAnotherQuarantine() {
    when(insert.update()).thenReturn(0);

    quarantine.accept(record("wallet", "{"), new IllegalArgumentException("Invalid JSON"));

    verify(transactions).commit(transaction);
    assertThat(count("wallet.kafka.quarantined")).isZero();
    assertThat(count("wallet.kafka.quarantine.failures")).isZero();
  }

  @Test
  void failedInsertRollsBackAndEscapesSoTheRecordCannotBeSkipped() {
    var failure = new DataAccessResourceFailureException("Quarantine unavailable");
    when(insert.update()).thenThrow(failure);

    assertThatThrownBy(
            () -> quarantine.accept(record("wallet", "{"), new IllegalArgumentException("Invalid")))
        .isSameAs(failure);

    verify(transactions).rollback(transaction);
    verify(transactions, never()).commit(any());
    assertThat(count("wallet.kafka.quarantined")).isZero();
    assertThat(count("wallet.kafka.quarantine.failures")).isEqualTo(1);
  }

  @Test
  void failedCommitEscapesWithoutCountingAnUnconfirmedQuarantine() {
    var failure = new TransactionSystemException("Commit failed");
    doThrow(failure).when(transactions).commit(transaction);

    assertThatThrownBy(
            () -> quarantine.accept(record("wallet", "{"), new IllegalArgumentException("Invalid")))
        .isSameAs(failure);

    verify(insert).update();
    assertThat(count("wallet.kafka.quarantined")).isZero();
    assertThat(count("wallet.kafka.quarantine.failures")).isEqualTo(1);
  }

  @Test
  void failedTransactionStartEscapesBeforeAnyQuarantineWrite() {
    var failure = new CannotCreateTransactionException("Database unavailable");
    when(transactions.getTransaction(any())).thenThrow(failure);

    assertThatThrownBy(
            () -> quarantine.accept(record("wallet", "{"), new IllegalArgumentException("Invalid")))
        .isSameAs(failure);

    verifyNoInteractions(jdbc, insert);
    verify(transactions, never()).commit(any());
    assertThat(count("wallet.kafka.quarantined")).isZero();
    assertThat(count("wallet.kafka.quarantine.failures")).isEqualTo(1);
  }

  private double count(String name) {
    var counter = metrics.find(name).counter();
    return counter == null ? 0 : counter.count();
  }

  private ConsumerRecord<String, String> record(String key, String payload) {
    return new ConsumerRecord<>(
        TOPIC,
        2,
        42,
        TIMESTAMP,
        TimestampType.CREATE_TIME,
        -1,
        -1,
        key,
        payload,
        new RecordHeaders(),
        Optional.empty());
  }
}
