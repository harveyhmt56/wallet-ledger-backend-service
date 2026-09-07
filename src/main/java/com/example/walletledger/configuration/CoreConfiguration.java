package com.example.walletledger.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictNumbers() {
    return builder ->
        builder
            .featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
