package com.example.walletledger.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  @Profile("local")
  UserDetailsService demoUsers() {
    return new InMemoryUserDetailsManager(
        User.withUsername("service").password("{noop}service-password").roles("SERVICE").build(),
        User.withUsername("admin").password("{noop}admin-password").roles("ADMIN").build(),
        User.withUsername("10000000-0000-0000-0000-000000000001")
            .password("{noop}alice-password")
            .roles("PLAYER")
            .build(),
        User.withUsername("10000000-0000-0000-0000-000000000002")
            .password("{noop}bob-password")
            .roles("PLAYER")
            .build());
  }

  @Bean
  @Profile("local")
  SecurityFilterChain local(
      HttpSecurity http,
      ObjectMapper json,
      RedisRateLimiter limiter,
      @Value("${ledger.rate-limit.enabled:true}") boolean enabled)
      throws Exception {
    return base(http, json, limiter, enabled)
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(
                    (request, response, error) ->
                        writeProblem(
                            json, response, 401, "UNAUTHENTICATED", "Authentication required")))
        .build();
  }

  @Bean
  @Profile("!local")
  SecurityFilterChain jwt(
      HttpSecurity http,
      ObjectMapper json,
      RedisRateLimiter limiter,
      @Value("${ledger.rate-limit.enabled:true}") boolean enabled)
      throws Exception {
    var roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return base(http, json, limiter, enabled)
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            writeProblem(
                                json,
                                response,
                                401,
                                "UNAUTHENTICATED",
                                "Valid access token required")))
        .build();
  }

  @Bean
  RedisRateLimiter limiter(
      StringRedisTemplate redis,
      MeterRegistry metrics,
      @Value("${ledger.rate-limit.requests:120}") int limit,
      @Value("${ledger.rate-limit.window-seconds:60}") int seconds) {
    return new RedisRateLimiter(redis, metrics, limit, seconds);
  }

  private HttpSecurity base(
      HttpSecurity http, ObjectMapper json, RedisRateLimiter limiter, boolean enabled)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            writeProblem(
                                json, response, 401, "UNAUTHENTICATED", "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            writeProblem(
                                json,
                                response,
                                403,
                                "FORBIDDEN",
                                "You do not have permission for this operation")))
        .addFilterBefore(new RateLimitFilter(limiter, json, enabled), AuthorizationFilter.class);
  }

  static void writeProblem(
      ObjectMapper json, HttpServletResponse response, int status, String code, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    json.writeValue(
        response.getOutputStream(),
        Map.of(
            "type",
            "about:blank",
            "status",
            status,
            "title",
            code,
            "code",
            code,
            "detail",
            detail));
  }
}
