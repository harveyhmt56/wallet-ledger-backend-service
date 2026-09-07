package com.example.walletledger.messaging.kafka;

import com.example.walletledger.messaging.outbox.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "ledger.outbox.enabled",
      havingValue = "true",
      matchIfMissing = true)
  OutboxRelay relay(
      JdbcClient jdbc,
      PlatformTransactionManager manager,
      KafkaTemplate<String, String> kafka,
      MeterRegistry metrics) {
    return new OutboxRelay(jdbc, manager, kafka, metrics);
  }

  @Bean
  @ConditionalOnProperty(
      name = "ledger.outbox.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic balanceTopic() {
    return TopicBuilder.name(OutboxRelay.TOPIC).partitions(3).replicas(1).build();
  }

  @Bean
  @ConditionalOnProperty(
      name = "ledger.consumer.enabled",
      havingValue = "true",
      matchIfMissing = true)
  BalanceListener listener(BalanceProjection projection) {
    return new BalanceListener(projection);
  }

  @Bean
  DefaultErrorHandler consumerErrors() {
    return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }

  public static class BalanceListener {
    private final BalanceProjection projection;

    BalanceListener(BalanceProjection projection) {
      this.projection = projection;
    }

    @KafkaListener(topics = OutboxRelay.TOPIC)
    public void receive(String payload) {
      projection.accept(payload);
    }
  }
}
