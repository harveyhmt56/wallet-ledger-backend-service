package com.example.walletledger.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MessagingConfigurationTest {
  private final KafkaQuarantine quarantine = mock(KafkaQuarantine.class);
  private final DefaultErrorHandler errors =
      new MessagingConfiguration().consumerErrors(quarantine);
  private final ConsumerRecord<String, String> record =
      new ConsumerRecord<>("wallet.balance-changed.v1", 1, 42, "wallet-key", "{");
  private final Consumer<?, ?> consumer = mock(Consumer.class);
  private final MessageListenerContainer container = mock(MessageListenerContainer.class);

  @Test
  void invalidEventIsQuarantinedOnTheFirstFailureEvenWhenWrappedByTheListener() {
    var failure =
        new ListenerExecutionFailedException(
            "Listener failed", "projection-test", new IllegalArgumentException("Invalid event"));

    assertThat(errors.handleOne(failure, record, consumer, container)).isTrue();

    verify(quarantine).accept(record, failure);
    assertThat(errors.isAckAfterHandle()).isTrue();
    assertThat(errors.seeksAfterHandling()).isTrue();
    verifyNoInteractions(consumer);
  }

  @Test
  void retryableFailureGetsThreeDeliveryAttemptsBeforeQuarantine() {
    var failure = new DataAccessResourceFailureException("Database unavailable");

    assertThat(errors.handleOne(failure, record, consumer, container)).isFalse();
    verifyNoInteractions(quarantine);
    assertThat(errors.handleOne(failure, record, consumer, container)).isFalse();
    verifyNoInteractions(quarantine);
    assertThat(errors.handleOne(failure, record, consumer, container)).isTrue();

    verify(quarantine).accept(record, failure);
    verifyNoInteractions(consumer);
  }

  @Test
  void changingRetryableFailureTypeDoesNotRestartTheSameRecordsRetryBudget() {
    var unavailable = new DataAccessResourceFailureException("Database unavailable");
    var locked = new CannotAcquireLockException("Database lock unavailable");

    assertThat(errors.handleOne(unavailable, record, consumer, container)).isFalse();
    assertThat(errors.handleOne(locked, record, consumer, container)).isFalse();
    verifyNoInteractions(quarantine);
    assertThat(errors.handleOne(unavailable, record, consumer, container)).isTrue();

    verify(quarantine).accept(record, unavailable);
  }

  @Test
  void failedQuarantineDoesNotRecoverTheRecordAndCanSucceedOnRedelivery() {
    var failure = new IllegalArgumentException("Invalid event");
    doThrow(new DataAccessResourceFailureException("Quarantine unavailable"))
        .doNothing()
        .when(quarantine)
        .accept(record, failure);

    assertThat(errors.handleOne(failure, record, consumer, container)).isFalse();
    assertThat(errors.handleOne(failure, record, consumer, container)).isTrue();

    verify(quarantine, times(2)).accept(record, failure);
    verifyNoInteractions(consumer);
  }

  @Test
  void quarantineBeanUsesTheConfiguredGroupDatabaseAndMetrics() {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec insert = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    var transaction = new SimpleTransactionStatus();
    when(transactions.getTransaction(any())).thenReturn(transaction);
    when(jdbc.sql(anyString())).thenReturn(insert);
    when(insert.update()).thenReturn(1);
    var metrics = new SimpleMeterRegistry();
    try {
      var configured =
          new MessagingConfiguration().quarantine(jdbc, transactions, metrics, "configured-group");

      configured.accept(record, new IllegalArgumentException("Invalid event"));

      verify(insert).param("group", "configured-group");
      verify(transactions).commit(transaction);
      assertThat(metrics.get("wallet.kafka.quarantined").counter().count()).isEqualTo(1);
    } finally {
      metrics.close();
    }
  }

  @Test
  void listenerDelegatesAndLetsProjectionFailureReachTheContainer() {
    BalanceProjection projection = mock(BalanceProjection.class);
    var failure = new IllegalArgumentException("Invalid event");
    doThrow(failure).when(projection).accept("invalid payload");
    var listener = new MessagingConfiguration().listener(projection);

    assertThatThrownBy(() -> listener.receive("invalid payload")).isSameAs(failure);

    verify(projection).accept("invalid payload");
  }
}
