package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {
  private final ObjectMapper json = new ObjectMapper();
  private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
  private final FilterChain chain = mock(FilterChain.class);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void exhaustedCallerGetsProblemResponseAndNeverReachesTheApplication() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(player());
    when(limiter.allow("alice")).thenReturn(false);
    var request = new MockHttpServletRequest("POST", "/v1/transfers");
    var response = new MockHttpServletResponse();

    new RateLimitFilter(limiter, json, true).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    var body = json.readTree(response.getContentAsString());
    assertThat(body.path("status").asInt()).isEqualTo(429);
    assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(body.path("detail").asText()).isNotBlank();
    verify(limiter).allow("alice");
    verifyNoInteractions(chain);
  }

  @Test
  void callerWithinQuotaReachesTheApplicationWithoutRetryHeaders() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(player());
    when(limiter.allow("alice")).thenReturn(true);
    var request = new MockHttpServletRequest("POST", "/v1/transfers");
    var response = new MockHttpServletResponse();

    new RateLimitFilter(limiter, json, true).doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(limiter).allow("alice");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Retry-After")).isNull();
    assertThat(response.getContentAsString()).isEmpty();
  }

  @ParameterizedTest(name = "quota is not consumed for {0}")
  @MethodSource("bypasses")
  void ineligibleRequestsDoNotConsumeQuota(
      String description, boolean enabled, String path, Authentication caller) throws Exception {
    SecurityContextHolder.getContext().setAuthentication(caller);
    var request = new MockHttpServletRequest("GET", path);
    var response = new MockHttpServletResponse();

    new RateLimitFilter(limiter, json, enabled).doFilter(request, response, chain);

    verifyNoInteractions(limiter);
    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  static Stream<Arguments> bypasses() {
    return Stream.of(
        Arguments.of("disabled limiter", false, "/v1/transfers", player()),
        Arguments.of("health probe", true, "/actuator/health/readiness", player()),
        Arguments.of("missing authentication", true, "/v1/transfers", null),
        Arguments.of(
            "unauthenticated caller",
            true,
            "/v1/transfers",
            UsernamePasswordAuthenticationToken.unauthenticated("alice", "unused")),
        Arguments.of(
            "anonymous token",
            true,
            "/v1/transfers",
            new AnonymousAuthenticationToken(
                "test", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
  }

  private static Authentication player() {
    return UsernamePasswordAuthenticationToken.authenticated(
        "alice", "unused", List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
  }
}
