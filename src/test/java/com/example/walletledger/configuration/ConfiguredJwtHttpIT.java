package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real HTTP, Boot's configured decoder and disposable PostgreSQL; JWKs stay on loopback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = "jwt-test", inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConfiguredJwtHttpIT extends PostgresIntegrationTest {
  private static final String AUDIENCE = "wallet-api";
  private static final LocalJwks JWKS = new LocalJwks();

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry properties) {
    String prefix = "spring.security.oauth2.resourceserver.jwt.";
    properties.add(prefix + "issuer-uri", JWKS::issuer);
    properties.add(prefix + "jwk-set-uri", () -> JWKS.issuer() + "/jwks");
    properties.add(prefix + "audiences", () -> AUDIENCE);
  }

  @AfterAll
  static void stopJwks() {
    JWKS.server.stop(0);
  }

  @Test
  void validSignatureIssuerAudienceSubjectAndRolesReachTheRealWalletController() throws Exception {
    UUID player = UUID.randomUUID();
    var provision =
        provision(player, UUID.randomUUID().toString(), token("service", "SERVICE", null));
    assertThat(provision.getStatusCode().value()).isEqualTo(200);

    var owner = balance(player, token(player.toString(), "PLAYER", null));
    assertThat(owner.getStatusCode().value()).isEqualTo(200);
    assertThat(owner.getBody().path("balance").asLong()).isZero();

    var otherPlayer = balance(player, token(UUID.randomUUID().toString(), "PLAYER", null));
    assertThat(otherPlayer.getStatusCode().value()).isEqualTo(403);
    assertThat(otherPlayer.getBody().path("code").asText()).isEqualTo("FORBIDDEN");
  }

  @ParameterizedTest
  @EnumSource(InvalidToken.class)
  void invalidTokenCannotReachMoneyControllersOrReserveAnIdempotencyKey(InvalidToken invalid)
      throws Exception {
    UUID player = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    var rejected = provision(player, key, token("service", "SERVICE", invalid));
    assertThat(rejected.getStatusCode().value()).isEqualTo(401);
    assertThat(
            rejected
                .getHeaders()
                .getContentType()
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .isTrue();
    assertThat(rejected.getBody().path("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from player where player_id=?", Integer.class, player))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_request where request_key=?", Integer.class, key))
        .isZero();

    // The identical command succeeds when the same caller presents a valid signed token.
    assertThat(provision(player, key, token("service", "SERVICE", null)).getStatusCode().value())
        .isEqualTo(200);
  }

  @Test
  void nonlocalProfileRejectsLocalDemonstrationBasicCredentials() {
    var response =
        http.withBasicAuth("admin", "admin-password")
            .getForEntity("/v1/admin/reconciliation", JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"SERVICE", "ADMIN"})
  void validSignedTokenWithoutPlayerRoleCannotTransfer(String role) throws Exception {
    String key = UUID.randomUUID().toString();
    var headers = bearer(token(UUID.randomUUID().toString(), role, null));
    headers.set("Idempotency-Key", key);
    var rejected =
        http.exchange(
            "/v1/transfers",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "recipientId",
                    UUID.randomUUID(),
                    "amount",
                    1,
                    "reason",
                    "JWT role check",
                    "source",
                    "jwt-http-test",
                    "reference",
                    key),
                headers),
            JsonNode.class);
    assertThat(rejected.getStatusCode().value()).isEqualTo(403);
    assertThat(rejected.getBody().path("code").asText()).isEqualTo("FORBIDDEN");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_request where request_key=?", Integer.class, key))
        .isZero();
  }

  private ResponseEntity<JsonNode> provision(UUID player, String key, String token) {
    var headers = bearer(token);
    headers.set("Idempotency-Key", key);
    return http.exchange(
        "/v1/players",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("playerId", player), headers),
        JsonNode.class);
  }

  private ResponseEntity<JsonNode> balance(UUID player, String token) {
    return http.exchange(
        "/v1/wallets/" + player + "/balance",
        HttpMethod.GET,
        new HttpEntity<>(bearer(token)),
        JsonNode.class);
  }

  private HttpHeaders bearer(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private String token(String subject, String role, InvalidToken invalid) throws JOSEException {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issueTime(Date.from(now.minusSeconds(600)))
            .expirationTime(Date.from(now.plusSeconds(300)));
    if (role != null) {
      claims.claim("roles", List.of(role));
    }
    if (invalid != InvalidToken.MISSING_ISSUER) {
      claims.issuer(
          invalid == InvalidToken.WRONG_ISSUER ? "https://wrong.example.test" : JWKS.issuer());
    }
    if (invalid != InvalidToken.MISSING_AUDIENCE) {
      claims.audience(
          invalid == InvalidToken.WRONG_AUDIENCE
              ? List.of("different-api")
              : List.of("another-api", AUDIENCE));
    }
    if (invalid == InvalidToken.EXPIRED) {
      claims.expirationTime(Date.from(now.minusSeconds(300)));
    }
    if (invalid == InvalidToken.NOT_YET_VALID) {
      claims.notBeforeTime(Date.from(now.plusSeconds(300)));
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
    jwt.sign(
        new RSASSASigner(
            invalid == InvalidToken.INVALID_SIGNATURE ? JWKS.untrustedKey : JWKS.signingKey));
    return jwt.serialize();
  }

  enum InvalidToken {
    WRONG_ISSUER,
    MISSING_ISSUER,
    WRONG_AUDIENCE,
    MISSING_AUDIENCE,
    EXPIRED,
    NOT_YET_VALID,
    INVALID_SIGNATURE
  }

  private static final class LocalJwks {
    private final RSAKey signingKey;
    private final RSAKey untrustedKey;
    private final HttpServer server;

    private LocalJwks() {
      try {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        untrustedKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] publicKeys =
            new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/jwks",
            exchange -> {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, publicKeys.length);
              try (var body = exchange.getResponseBody()) {
                body.write(publicKeys);
              }
            });
        server.start();
      } catch (JOSEException | IOException error) {
        throw new IllegalStateException("Could not start local JWK test fixture", error);
      }
    }

    private String issuer() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }
  }
}
