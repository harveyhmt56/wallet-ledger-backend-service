package com.example.walletledger.configuration;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

@WebMvcTest(
    controllers = JwtSecurityTest.Probe.class,
    properties = {
      "ledger.rate-limit.enabled=false",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
      "spring.security.oauth2.resourceserver.jwt.audiences=wallet-api"
    })
@Import({SecurityConfiguration.class, JwtSecurityTest.Probe.class})
@ActiveProfiles("jwt-test")
class JwtSecurityTest {
  @Autowired MockMvc http;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean StringRedisTemplate redis;
  @MockitoBean MeterRegistry metrics;

  @Test
  void verifiedSubjectAndRolesReachAuthorizationWhileInvalidTokenIsRejected() throws Exception {
    var token =
        Jwt.withTokenValue("valid")
            .header("alg", "RS256")
            .subject("10000000-0000-0000-0000-000000000001")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("roles", List.of("PLAYER"))
            .build();
    when(decoder.decode("valid")).thenReturn(token);
    when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid signature"));
    http.perform(get("/probe").header("Authorization", "Bearer valid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value(token.getSubject()));
    http.perform(get("/probe").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @RestController
  static class Probe {
    @GetMapping("/probe")
    @PreAuthorize("hasRole('PLAYER')")
    Map<String, String> get(Authentication auth) {
      return Map.of("subject", auth.getName());
    }
  }
}
