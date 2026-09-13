package com.example.walletledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.example.walletledger.messaging.outbox.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboxRelayTest {
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final JdbcClient.StatementSpec delivered =
      mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
  private final JdbcClient.StatementSpec failed =
      mock(JdbcClient.StatementSpec.class, RETURNS_SELF);

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Test
  void acknowledgedDeliveryPublishesTheWalletKeyAndRecordsSuccess() throws Exception {
    UUID event = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    OutboxRelay relay = relayWithEvents(event, wallet);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(delivered.update()).thenReturn(1);

    assertThat(relay.relayBatch()).isEqualTo(1);

    verify(kafka).send(OutboxRelay.TOPIC, wallet.toString(), "payload-0");
    verify(delivered).param("id", event);
    assertThat(metrics.get("wallet.outbox.delivered").counter().count()).isEqualTo(1);
    assertThat(metrics.find("wallet.outbox.failures").counter()).isNull();
    verifyNoInteractions(failed);
  }

  @Test
  void failedSendIsNotAcknowledgedAndRecordsFailure() throws Exception {
    UUID event = UUID.randomUUID();
    OutboxRelay relay = relayWithEvents(event, UUID.randomUUID());
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

    assertThat(relay.relayBatch()).isZero();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();

    verify(failed).param("id", event);
    verify(failed).update();
    verifyNoInteractions(delivered);
    assertThat(metrics.get("wallet.outbox.failures").counter().count()).isEqualTo(1);
    assertThat(metrics.find("wallet.outbox.delivered").counter()).isNull();
  }

  @Test
  void interruptedSendKeepsInterruptFlagAndStopsBeforeTheNextEvent() throws Exception {
    UUID first = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    OutboxRelay relay = relayWithEvents(first, wallet, UUID.randomUUID(), UUID.randomUUID());
    CompletableFuture<SendResult<String, String>> waiting = new CompletableFuture<>();
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(waiting)
        .thenThrow(new AssertionError("Interrupted relay must not send the next event"));

    Thread.currentThread().interrupt();
    try {
      assertThat(relay.relayBatch()).isZero();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(kafka, times(1)).send(OutboxRelay.TOPIC, wallet.toString(), "payload-0");
      verifyNoMoreInteractions(kafka);
      verify(failed).param("id", first);
      verify(failed).param("error", "InterruptedException");
      assertThat(metrics.get("wallet.outbox.failures").counter().count()).isEqualTo(1);
      verifyNoInteractions(delivered);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void scheduledDatabaseFailureIsCountedWithoutEscapingTheScheduler() {
    OutboxRelay relay = new OutboxRelay(jdbc, transactions, kafka, metrics);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(jdbc.sql(anyString()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatCode(relay::scheduledRelay).doesNotThrowAnyException();

    assertThat(metrics.get("wallet.outbox.failures").counter().count()).isEqualTo(1);
    verifyNoInteractions(kafka);
  }

  @SuppressWarnings("unchecked")
  private OutboxRelay relayWithEvents(UUID... eventAndWalletPairs) throws Exception {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    JdbcClient.StatementSpec claim = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    JdbcClient.MappedQuerySpec<Object> rows = mock(JdbcClient.MappedQuerySpec.class);
    when(jdbc.sql(anyString()))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.contains("with candidates as")) return claim;
              if (query.startsWith("update outbox_event set delivered_at=")) return delivered;
              if (query.startsWith("update outbox_event set lease_until=")) return failed;
              throw new AssertionError("Unexpected database operation: " + query);
            });
    when(claim.query(any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(0);
              List<Object> claimed = new ArrayList<>();
              for (int index = 0; index < eventAndWalletPairs.length; index += 2) {
                ResultSet row = mock(ResultSet.class);
                when(row.getObject(1, UUID.class)).thenReturn(eventAndWalletPairs[index]);
                when(row.getObject(2, UUID.class)).thenReturn(eventAndWalletPairs[index + 1]);
                when(row.getString(3)).thenReturn("payload-" + index / 2);
                claimed.add(mapper.mapRow(row, index / 2));
              }
              when(rows.list()).thenReturn(claimed);
              return rows;
            });
    return new OutboxRelay(jdbc, transactions, kafka, metrics);
  }
}
