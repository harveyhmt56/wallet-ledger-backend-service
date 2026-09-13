package com.example.walletledger.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.walletledger.messaging.kafka.BalanceProjection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class BalanceProjectionTest {
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final BalanceProjection projection = new BalanceProjection(jdbc, new ObjectMapper());

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"eventId\":", "[1,"})
  void malformedJsonIsRejectedBeforeAnyDatabaseWrite(String payload) {
    assertThatThrownBy(() -> projection.accept(payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid balance event JSON")
        .hasCauseInstanceOf(JsonProcessingException.class);
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "null", "{}", "[]", "true", "10", "\"event\""})
  void payloadWithoutEventIdentifiersIsRejectedBeforeAnyDatabaseWrite(String payload) {
    assertThatThrownBy(() -> projection.accept(payload))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidFields")
  void invalidRequiredFieldIsRejectedBeforeAnyDatabaseWrite(String field, String value)
      throws Exception {
    ObjectMapper json = new ObjectMapper();
    ObjectNode event =
        json.createObjectNode()
            .put("eventId", "11111111-1111-1111-1111-111111111111")
            .put("walletId", "22222222-2222-2222-2222-222222222222")
            .put("walletSequence", 1)
            .put("balanceAfter", 10)
            .put("schemaVersion", 1);
    if (value == null) event.remove(field);
    else event.set(field, json.readTree(value));

    assertThatThrownBy(() -> projection.accept(event.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc);
  }

  private static Stream<Arguments> invalidFields() {
    Stream<Arguments> identifiers =
        Stream.of("eventId", "walletId")
            .flatMap(
                field ->
                    Stream.of(null, "null", "\"invalid-uuid\"").map(v -> Arguments.of(field, v)));
    Stream<Arguments> numbers =
        Stream.of("walletSequence", "balanceAfter")
            .flatMap(
                field ->
                    Stream.of(null, "null", "\"1\"", "1.5", "true", "[]", "{}", "-1")
                        .map(v -> Arguments.of(field, v)));
    Stream<Arguments> versions =
        Stream.of(null, "null", "0", "2").map(v -> Arguments.of("schemaVersion", v));
    return Stream.of(identifiers, numbers, versions, Stream.of(Arguments.of("walletSequence", "0")))
        .flatMap(stream -> stream);
  }
}
