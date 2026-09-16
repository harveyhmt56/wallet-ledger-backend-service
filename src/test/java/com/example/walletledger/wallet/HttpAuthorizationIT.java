package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.walletledger.rewards.application.RewardService;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Real controller/method-security and PostgreSQL evidence; identity is supplied by Spring's test
 * support.
 */
@AutoConfigureMockMvc
class HttpAuthorizationIT extends PostgresIntegrationTest {
  private static final UUID REWARD = UUID.fromString("20000000-0000-0000-0000-000000000001");
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient jdbc;
  @Autowired WalletService wallets;
  @Autowired RewardService rewards;

  enum Caller {
    ANONYMOUS,
    ROLELESS,
    OWNER_PLAYER,
    OTHER_PLAYER,
    SERVICE,
    ADMIN
  }

  enum Endpoint {
    PROVISION,
    CREDIT,
    DEBIT,
    BALANCE,
    HISTORY,
    TRANSFER,
    REFUND,
    DAILY,
    REWARD_CLAIM,
    PROMOTION_CLAIM,
    COMPLETION,
    RECONCILIATION
  }

  static Stream<Arguments> authorizationMatrix() {
    return Stream.of(
            access(Endpoint.PROVISION, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.CREDIT, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.DEBIT, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.BALANCE, Caller.OWNER_PLAYER, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.HISTORY, Caller.OWNER_PLAYER, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.TRANSFER, Caller.OWNER_PLAYER, Caller.OTHER_PLAYER),
            access(Endpoint.REFUND, Caller.SERVICE, Caller.ADMIN),
            access(Endpoint.DAILY, Caller.OWNER_PLAYER, Caller.OTHER_PLAYER),
            access(Endpoint.REWARD_CLAIM, Caller.OWNER_PLAYER, Caller.OTHER_PLAYER),
            access(Endpoint.PROMOTION_CLAIM, Caller.OWNER_PLAYER, Caller.OTHER_PLAYER),
            access(Endpoint.COMPLETION, Caller.SERVICE),
            access(Endpoint.RECONCILIATION, Caller.ADMIN))
        .flatMap(
            rule ->
                Arrays.stream(Caller.values())
                    .map(
                        caller ->
                            Arguments.of(
                                rule.endpoint(), caller, rule.allowed().contains(caller))));
  }

  private static Access access(Endpoint endpoint, Caller... allowed) {
    return new Access(endpoint, Set.of(allowed));
  }

  @ParameterizedTest(name = "{0}: {1}, permitted={2}")
  @MethodSource("authorizationMatrix")
  void realEndpointsEnforceRolesAndOwnershipWithoutUnauthorizedWrites(
      Endpoint endpoint, Caller caller, boolean permitted) throws Exception {
    Fixture fixture = fixture();
    var before = snapshot(fixture);
    var request = request(endpoint, fixture, caller);
    int expectedStatus = permitted ? 200 : caller == Caller.ANONYMOUS ? 401 : 403;
    JsonNode response = perform(request, fixture, caller, expectedStatus);

    if (!permitted) {
      assertThat(response.path("code").asText())
          .isEqualTo(caller == Caller.ANONYMOUS ? "UNAUTHENTICATED" : "FORBIDDEN");
      assertThat(snapshot(fixture)).isEqualTo(before);
      return;
    }

    assertSuccessfulOperation(endpoint, caller, fixture, response);
    if (Set.of(Endpoint.BALANCE, Endpoint.HISTORY, Endpoint.RECONCILIATION).contains(endpoint)) {
      assertThat(snapshot(fixture)).isEqualTo(before);
    } else {
      assertThat(
              jdbc.sql("select status from idempotency_request where actor=? and request_key=?")
                  .params(subject(caller, fixture), fixture.key())
                  .query(Integer.class)
                  .single())
          .isEqualTo(200);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"amount", "playerId", "completed", "rewardId"})
  void rewardClaimRejectsClientControlledFieldsBeforeReservingAKey(String extraField)
      throws Exception {
    Fixture fixture = fixture();
    var before = snapshot(fixture);
    var body = json.createObjectNode().put("completionReference", fixture.ownerCompletion());
    body.put(extraField, "999999");
    JsonNode response =
        perform(
            command("/v1/rewards/" + REWARD + "/claims", body), fixture, Caller.OWNER_PLAYER, 400);
    assertThat(response.path("code").asText()).isEqualTo("INVALID_INPUT");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @Test
  void transferRejectsClientSuppliedSenderBeforeReservingAKey() throws Exception {
    Fixture fixture = fixture();
    var before = snapshot(fixture);
    var body = moneyBody(fixture);
    body.put("recipientId", fixture.recipient());
    body.put("senderId", fixture.other());
    JsonNode response = perform(command("/v1/transfers", body), fixture, Caller.OWNER_PLAYER, 400);
    assertThat(response.path("code").asText()).isEqualTo("INVALID_INPUT");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"daily", "promotion"})
  void bodylessClaimsUseAuthenticatedPlayerAndServerAmountDespiteClientSuppliedBody(String kind)
      throws Exception {
    Fixture fixture = fixture();
    String path =
        kind.equals("daily")
            ? "/v1/daily-login/claims"
            : "/v1/promotions/" + fixture.promotion() + "/claims";
    long expected = kind.equals("daily") ? 10 : 25;
    var response =
        perform(
            command(path, Map.of("amount", 999999, "playerId", fixture.other(), "completed", true)),
            fixture,
            Caller.OWNER_PLAYER,
            200);
    assertThat(response.path("amount").asLong()).isEqualTo(expected);
    assertThat(balance(fixture.owner())).isEqualTo(1000 + expected);
    assertThat(balance(fixture.other())).isEqualTo(1000);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nonexistentOrOtherPlayersCompletionCannotMintAReward(boolean anotherPlayer)
      throws Exception {
    Fixture fixture = fixture();
    var before = snapshot(fixture);
    before.remove("idempotency_request");
    String completion = anotherPlayer ? fixture.otherCompletion() : UUID.randomUUID().toString();
    int expectedStatus = anotherPlayer ? 403 : 404;
    var response =
        perform(
            command("/v1/rewards/" + REWARD + "/claims", Map.of("completionReference", completion)),
            fixture,
            Caller.OWNER_PLAYER,
            expectedStatus);
    assertThat(response.path("code").asText())
        .isEqualTo(anotherPlayer ? "COMPLETION_NOT_OWNED" : "COMPLETION_NOT_FOUND");
    var after = snapshot(fixture);
    after.remove("idempotency_request");
    assertThat(after).isEqualTo(before);
    // A domain rejection is deliberately stored; authorization/filter rejections are not.
    assertThat(
            jdbc.sql("select status from idempotency_request where actor=? and request_key=?")
                .params(fixture.owner().toString(), fixture.key())
                .query(Integer.class)
                .single())
        .isEqualTo(expectedStatus);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Caller.class)
  void healthComponentDetailsAreVisibleOnlyToAdministrators(Caller caller) throws Exception {
    Fixture fixture = fixture();
    JsonNode health = perform(get("/actuator/health"), fixture, caller, 200);
    assertThat(health.path("status").asText()).isEqualTo("UP");
    // The public probe reports liveness only; dependency inventory is administrator information.
    if (caller == Caller.ADMIN) {
      assertThat(health.path("components").path("db").path("status").asText()).isEqualTo("UP");
    } else {
      assertThat(health.has("components")).as("components for %s", caller).isFalse();
    }
  }

  private void assertSuccessfulOperation(
      Endpoint endpoint, Caller caller, Fixture fixture, JsonNode response) {
    UUID actingPlayer = caller == Caller.OTHER_PLAYER ? fixture.other() : fixture.owner();
    UUID untouchedPlayer = caller == Caller.OTHER_PLAYER ? fixture.owner() : fixture.other();
    switch (endpoint) {
      case PROVISION -> {
        assertThat(response.path("playerId").asText()).isEqualTo(fixture.newPlayer().toString());
        assertThat(balance(fixture.newPlayer())).isZero();
      }
      case CREDIT -> assertThat(balance(fixture.owner())).isEqualTo(1010);
      case DEBIT -> assertThat(balance(fixture.owner())).isEqualTo(990);
      case BALANCE -> {
        assertThat(response.path("playerId").asText()).isEqualTo(fixture.owner().toString());
        assertThat(response.path("balance").asLong()).isEqualTo(1000);
      }
      case HISTORY -> {
        assertThat(response.path("items")).hasSize(1);
        assertThat(response.path("items").get(0).path("transactionId").asText())
            .isEqualTo(fixture.originalCredit().toString());
      }
      case TRANSFER -> {
        assertThat(balance(actingPlayer)).isEqualTo(990);
        assertThat(balance(untouchedPlayer)).isEqualTo(1000);
        assertThat(balance(fixture.recipient())).isEqualTo(10);
        UUID transaction = UUID.fromString(response.path("transactionId").asText());
        assertThat(
                jdbc.sql("select actor from journal_transaction where transaction_id=?")
                    .param(transaction)
                    .query(String.class)
                    .single())
            .isEqualTo(actingPlayer.toString());
        assertThat(
                jdbc.sql("select wallet_id from ledger_entry where transaction_id=? and amount=-10")
                    .param(transaction)
                    .query(UUID.class)
                    .single())
            .isEqualTo(actingPlayer);
      }
      case REFUND -> {
        assertThat(balance(fixture.owner())).isZero();
        assertThat(response.path("originalTransactionId").asText())
            .isEqualTo(fixture.originalCredit().toString());
      }
      case DAILY, REWARD_CLAIM, PROMOTION_CLAIM -> {
        long amount =
            endpoint == Endpoint.DAILY ? 10 : endpoint == Endpoint.REWARD_CLAIM ? 100 : 25;
        assertThat(response.path("amount").asLong()).isEqualTo(amount);
        assertThat(balance(actingPlayer)).isEqualTo(1000 + amount);
        assertThat(balance(untouchedPlayer)).isEqualTo(1000);
        String table =
            endpoint == Endpoint.DAILY
                ? "daily_claim"
                : endpoint == Endpoint.REWARD_CLAIM ? "reward_claim" : "promotion_claim";
        assertThat(
                jdbc.sql("select amount from " + table + " where player_id=?")
                    .param(actingPlayer)
                    .query(Long.class)
                    .single())
            .isEqualTo(amount);
      }
      case COMPLETION -> {
        assertThat(response.path("playerId").asText()).isEqualTo(fixture.owner().toString());
        assertThat(
                jdbc.sql("select actor from action_completion where completion_id=?")
                    .param(UUID.fromString(response.path("completionReference").asText()))
                    .query(String.class)
                    .single())
            .isEqualTo("service");
        assertThat(balance(fixture.owner())).isEqualTo(1000);
      }
      case RECONCILIATION -> {
        assertThat(response.path("consistent").asBoolean()).isTrue();
        assertThat(response.path("mismatches")).isEmpty();
      }
    }
  }

  private MockHttpServletRequestBuilder request(Endpoint endpoint, Fixture fixture, Caller caller)
      throws Exception {
    String walletPath = "/v1/wallets/" + fixture.owner();
    var money = moneyBody(fixture);
    return switch (endpoint) {
      case PROVISION -> command("/v1/players", Map.of("playerId", fixture.newPlayer()));
      case CREDIT -> command(walletPath + "/credits", money);
      case DEBIT -> command(walletPath + "/debits", money);
      case BALANCE -> get(walletPath + "/balance");
      case HISTORY -> get(walletPath + "/transactions");
      case TRANSFER -> {
        money.put("recipientId", fixture.recipient());
        yield command("/v1/transfers", money);
      }
      case REFUND -> {
        money.remove("amount");
        yield command("/v1/transactions/" + fixture.originalCredit() + "/refunds", money);
      }
      case DAILY -> post("/v1/daily-login/claims");
      case REWARD_CLAIM ->
          command(
              "/v1/rewards/" + REWARD + "/claims",
              Map.of(
                  "completionReference",
                  caller == Caller.OTHER_PLAYER
                      ? fixture.otherCompletion()
                      : fixture.ownerCompletion()));
      case PROMOTION_CLAIM -> post("/v1/promotions/" + fixture.promotion() + "/claims");
      case COMPLETION ->
          command(
              "/internal/v1/action-completions",
              Map.of(
                  "playerId",
                  fixture.owner(),
                  "rewardId",
                  REWARD,
                  "source",
                  "http-authorization",
                  "reference",
                  fixture.key()));
      case RECONCILIATION -> get("/v1/admin/reconciliation");
    };
  }

  private MockHttpServletRequestBuilder command(String path, Object body) throws Exception {
    return post(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private JsonNode perform(
      MockHttpServletRequestBuilder request, Fixture fixture, Caller caller, int expectedStatus)
      throws Exception {
    request.header("Idempotency-Key", fixture.key());
    if (caller != Caller.ANONYMOUS) {
      String[] roles =
          switch (caller) {
            case OWNER_PLAYER, OTHER_PLAYER -> new String[] {"PLAYER"};
            case SERVICE -> new String[] {"SERVICE"};
            case ADMIN -> new String[] {"ADMIN"};
            default -> new String[0];
          };
      request.with(user(subject(caller, fixture)).roles(roles));
    }
    var result = http.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private String subject(Caller caller, Fixture fixture) {
    return switch (caller) {
      case SERVICE -> "service";
      case ADMIN -> "admin";
      case OTHER_PLAYER -> fixture.other().toString();
      default -> fixture.owner().toString();
    };
  }

  private Map<String, Object> moneyBody(Fixture fixture) {
    return new LinkedHashMap<>(
        Map.of(
            "amount",
            10,
            "reason",
            "authorization",
            "source",
            "http-authorization",
            "reference",
            fixture.key()));
  }

  private Fixture fixture() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    for (UUID player : List.of(owner, other, recipient)) wallets.provision(player);
    var original =
        wallets.credit(
            owner, 1000, "setup", "fund", "http-authorization", UUID.randomUUID().toString());
    wallets.credit(
        other, 1000, "setup", "fund", "http-authorization", UUID.randomUUID().toString());
    UUID promotion = UUID.randomUUID();
    migrationJdbc()
        .update(
            "insert into promotion(promotion_id,name,amount,capacity,policy_version) values (?,'Authorization test',25,2,'test-v1')",
            promotion);
    return new Fixture(
        owner,
        other,
        recipient,
        UUID.randomUUID(),
        promotion,
        UUID.fromString(original.get("transactionId").toString()),
        completion(owner),
        completion(other),
        UUID.randomUUID().toString());
  }

  private String completion(UUID player) {
    return rewards
        .completion(player, REWARD, "http-authorization", UUID.randomUUID().toString(), "setup")
        .get("completionReference")
        .toString();
  }

  private long balance(UUID player) {
    return jdbc.sql("select balance from wallet where player_id=?")
        .param(player)
        .query(Long.class)
        .single();
  }

  /**
   * Compare complete affected rows, including balances, sequences, claim state and reserved keys.
   */
  private Map<String, String> snapshot(Fixture fixture) {
    String playerScope = "player_id in (:players)";
    String transactionScope =
        "transaction_id in (select transaction_id from ledger_entry where wallet_id in (:players))";
    var predicates = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "player",
            "ledger_account",
            "wallet",
            "daily_streak",
            "daily_claim",
            "action_completion",
            "reward_claim")) {
      predicates.put(table, playerScope);
    }
    predicates.put("journal_transaction", transactionScope);
    predicates.put("ledger_entry", transactionScope);
    predicates.put("outbox_event", "wallet_id in (:players)");
    predicates.put("promotion", "promotion_id=:promotion");
    predicates.put("promotion_claim", "promotion_id=:promotion");
    predicates.put("idempotency_request", "request_key=:key");
    var state = new LinkedHashMap<String, String>();
    predicates.forEach(
        (table, predicate) ->
            state.put(
                table,
                jdbc.sql(
                        "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "
                            + table
                            + " t where "
                            + predicate)
                    .param(
                        "players",
                        List.of(
                            fixture.owner(),
                            fixture.other(),
                            fixture.recipient(),
                            fixture.newPlayer()))
                    .param("promotion", fixture.promotion())
                    .param("key", fixture.key())
                    .query(String.class)
                    .single()));
    return state;
  }

  private record Access(Endpoint endpoint, Set<Caller> allowed) {}

  private record Fixture(
      UUID owner,
      UUID other,
      UUID recipient,
      UUID newPlayer,
      UUID promotion,
      UUID originalCredit,
      String ownerCompletion,
      String otherCompletion,
      String key) {}
}
