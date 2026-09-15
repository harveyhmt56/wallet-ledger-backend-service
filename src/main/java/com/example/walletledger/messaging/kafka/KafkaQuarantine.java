package com.example.walletledger.messaging.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits recovery evidence before the listener may acknowledge the source record. */
public class KafkaQuarantine implements ConsumerRecordRecoverer {
  private static final Logger log = LoggerFactory.getLogger(KafkaQuarantine.class);
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final MeterRegistry metrics;
  private final String group;

  public KafkaQuarantine(
      JdbcClient jdbc, PlatformTransactionManager manager, MeterRegistry metrics, String group) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
    this.group = group;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Exception failure) {
    String error = NestedExceptionUtils.getMostSpecificCause(failure).getClass().getName();
    int inserted;
    try {
      inserted =
          transaction.execute(
              status ->
                  jdbc.sql(
                          """
          insert into kafka_quarantine
            (consumer_group,topic,partition_id,record_offset,record_key,payload,record_timestamp,error_type)
          values (:group,:topic,:partition,:offset,:key,:payload,:timestamp,:error)
          on conflict (consumer_group,topic,partition_id,record_offset) do nothing
          """)
                      .param("group", group)
                      .param("topic", record.topic())
                      .param("partition", record.partition())
                      .param("offset", record.offset())
                      .param("key", bytes(record.key()), Types.BINARY)
                      .param("payload", bytes(record.value()), Types.BINARY)
                      .param("timestamp", record.timestamp())
                      .param("error", error)
                      .update());
    } catch (RuntimeException unavailable) {
      metrics.counter("wallet.kafka.quarantine.failures").increment();
      log.error("Kafka quarantine failed: {}", unavailable.getClass().getName());
      throw unavailable;
    }
    if (inserted > 0) {
      metrics.counter("wallet.kafka.quarantined").increment();
      log.error(
          "Kafka record quarantined: group={}, topic={}, partition={}, offset={}, error={}",
          group,
          record.topic(),
          record.partition(),
          record.offset(),
          error);
    }
  }

  private static byte[] bytes(Object value) {
    return value == null ? null : ((String) value).getBytes(StandardCharsets.UTF_8);
  }
}
