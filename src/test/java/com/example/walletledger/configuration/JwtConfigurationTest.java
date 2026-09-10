package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

class JwtConfigurationTest {
  private static final String PREFIX = "spring.security.oauth2.resourceserver.jwt.";

  private final WebApplicationContextRunner context =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SecurityAutoConfiguration.class,
                  OAuth2ResourceServerAutoConfiguration.class,
                  WebMvcAutoConfiguration.class))
          .withUserConfiguration(SecurityConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class));

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " \t ")
  void nonlocalStartupRequiresNonblankIssuerEvenWhenADecoderExists(String issuer) {
    var properties = new HashMap<String, Object>();
    properties.put(PREFIX + "audiences[0]", "wallet-api");
    if (issuer != null) {
      properties.put(PREFIX + "issuer-uri", issuer);
    }
    configured(properties)
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(application.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasStackTraceContaining(PREFIX + "issuer-uri");
            });
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " \t ")
  void nonlocalStartupRequiresNonblankAudienceEvenWhenADecoderExists(String audience) {
    var properties = new HashMap<String, Object>();
    properties.put(PREFIX + "issuer-uri", "https://issuer.example.test");
    if (audience != null) {
      properties.put(PREFIX + "audiences[0]", audience);
    }
    configured(properties)
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(application.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasStackTraceContaining(PREFIX + "audiences");
            });
  }

  @Test
  void nonlocalStartupRejectsBlankMemberAmongConfiguredAudiences() {
    configured(
            Map.of(
                PREFIX + "issuer-uri", "https://issuer.example.test",
                PREFIX + "audiences[0]", "wallet-api",
                PREFIX + "audiences[1]", " "))
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(application.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasStackTraceContaining(PREFIX + "audiences");
            });
  }

  @Test
  void nonlocalStartupAcceptsIssuerAndNonblankAudiences() {
    configured(
            Map.of(
                PREFIX + "issuer-uri", "https://issuer.example.test",
                PREFIX + "audiences[0]", "wallet-api",
                PREFIX + "audiences[1]", "wallet-api-secondary"))
        .run(
            application -> {
              assertThat(application).hasNotFailed().hasSingleBean(SecurityFilterChain.class);
              assertThat(application).hasBean("jwt").doesNotHaveBean("demoUsers");
            });
  }

  @Test
  void localDemonstrationStillStartsWithoutJwtSettings() {
    context
        .withPropertyValues("spring.profiles.active=local")
        .run(
            application -> {
              assertThat(application).hasNotFailed().hasSingleBean(SecurityFilterChain.class);
              assertThat(application).hasBean("demoUsers").hasBean("local").doesNotHaveBean("jwt");
            });
  }

  private WebApplicationContextRunner configured(Map<String, Object> properties) {
    return context.withInitializer(
        application ->
            application
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("jwt-test-settings", properties)));
  }
}
