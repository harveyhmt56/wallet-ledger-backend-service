package com.example.walletledger.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class RateLimitFilter extends OncePerRequestFilter {
  private final RedisRateLimiter limiter;
  private final ObjectMapper json;
  private final boolean enabled;

  RateLimitFilter(RedisRateLimiter limiter, ObjectMapper json, boolean enabled) {
    this.limiter = limiter;
    this.json = json;
    this.enabled = enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var caller = SecurityContextHolder.getContext().getAuthentication();
    if (enabled
        && !request.getRequestURI().startsWith("/actuator/")
        && caller != null
        && caller.isAuthenticated()
        && !(caller instanceof AnonymousAuthenticationToken)
        && !limiter.allow(caller.getName())) {
      response.setHeader("Retry-After", "60");
      SecurityConfiguration.writeProblem(
          json, response, 429, "RATE_LIMITED", "Request quota exceeded; retry later");
      return;
    }
    chain.doFilter(request, response);
  }
}
