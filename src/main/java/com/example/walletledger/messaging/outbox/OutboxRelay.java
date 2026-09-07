package com.example.walletledger.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxRelay {
  public static final String TOPIC = "wallet.balance-changed.v1";
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final KafkaTemplate<String, String> kafka;
  private final MeterRegistry metrics;

  public OutboxRelay(
      JdbcClient jdbc,
      PlatformTransactionManager manager,
      KafkaTemplate<String, String> kafka,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.kafka = kafka;
    this.metrics = metrics;
    Gauge.builder("wallet.outbox.pending", this, relay -> relay.pending()).register(metrics);
    Gauge.builder("wallet.outbox.oldest_age_seconds", this, relay -> relay.oldestAge())
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${ledger.outbox.delay-ms:1000}")
  public void scheduledRelay() {
    try {
      relayBatch();
    } catch (RuntimeException failure) {
      metrics.counter("wallet.outbox.failures").increment();
      log.warn("Outbox batch failed: {}", failure.getClass().getSimpleName());
    }
  }

  public int relayBatch() {
    UUID token = UUID.randomUUID();
    List<Pending> batch =
        transaction.execute(
            status ->
                jdbc.sql(
                        """
        with candidates as (
          select event_id from outbox_event
          where delivered_at is null and (lease_until is null or lease_until < now())
          order by created_at,event_id for update skip locked limit 100
        )
        update outbox_event o set lease_until=now()+interval '60 seconds',lease_token=:token,attempts=o.attempts+1
        from candidates c where o.event_id=c.event_id
        returning o.event_id,o.wallet_id,o.payload::text
        """)
                    .param("token", token)
                    .query(
                        (rs, n) ->
                            new Pending(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getString(3)))
                    .list());
    int delivered = 0;
    for (Pending event : batch) {
      try {
        kafka.send(TOPIC, event.wallet().toString(), event.payload()).get(3, TimeUnit.SECONDS);
        delivered +=
            jdbc.sql(
                    "update outbox_event set delivered_at=now(),lease_until=null,lease_token=null,last_error=null where event_id=:id and lease_token=:token")
                .param("id", event.id())
                .param("token", token)
                .update();
        metrics.counter("wallet.outbox.delivered").increment();
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        jdbc.sql(
                "update outbox_event set lease_until=now()+least(attempts,30)*interval '1 second',last_error=:error where event_id=:id and lease_token=:token")
            .param("error", failure.getClass().getSimpleName())
            .param("id", event.id())
            .param("token", token)
            .update();
        metrics.counter("wallet.outbox.failures").increment();
        if (Thread.currentThread().isInterrupted()) break;
      }
    }
    return delivered;
  }

  double pending() {
    return jdbc.sql("select count(*) from outbox_event where delivered_at is null")
        .query(Long.class)
        .single();
  }

  double oldestAge() {
    return jdbc.sql(
            "select coalesce(extract(epoch from now()-min(created_at)),0)::double precision from outbox_event where delivered_at is null")
        .query(Double.class)
        .single();
  }

  private record Pending(UUID id, UUID wallet, String payload) {}
}
