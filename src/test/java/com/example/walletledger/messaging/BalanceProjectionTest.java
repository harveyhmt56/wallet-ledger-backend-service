package com.example.walletledger.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.walletledger.messaging.kafka.BalanceProjection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
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
  void invalidRequiredFieldIsRejectedBeforeAnyDatabaseWrite(String field, String value) {
    ObjectNode event = event(1, 10);
    if (value == null) event.remove(field);
    else event.putRawValue(field, new RawValue(value));

    assertThatThrownBy(() -> projection.accept(event.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest
  @CsvSource({"1, 0", "2147483648, 2147483648", "9223372036854775807, 9223372036854775807"})
  void exactIntegerBoundariesAreWrittenWithoutNarrowing(long sequence, long balance) {
    var consumed = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    var create = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    var current = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    var update = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    JdbcClient.MappedQuerySpec<Long> result = mock(JdbcClient.MappedQuerySpec.class);
    when(jdbc.sql(
            "insert into consumed_event(event_id,wallet_id) values (:id,:wallet) on conflict do nothing"))
        .thenReturn(consumed);
    when(consumed.update()).thenReturn(1);
    when(jdbc.sql(
            "insert into wallet_projection(wallet_id,wallet_sequence,balance) values (:wallet,0,0) on conflict do nothing"))
        .thenReturn(create);
    when(jdbc.sql(
            "select wallet_sequence from wallet_projection where wallet_id=:wallet for update"))
        .thenReturn(current);
    when(current.query(Long.class)).thenReturn(result);
    when(result.single()).thenReturn(0L);
    when(jdbc.sql(
            "update wallet_projection set wallet_sequence=:sequence,balance=:balance where wallet_id=:wallet"))
        .thenReturn(update);

    projection.accept(event(sequence, balance).toString());

    verify(update).param("sequence", sequence);
    verify(update).param("balance", balance);
    verify(update).update();
  }

  private static ObjectNode event(long sequence, long balance) {
    return new ObjectMapper()
        .createObjectNode()
        .put("eventId", "11111111-1111-1111-1111-111111111111")
        .put("walletId", "22222222-2222-2222-2222-222222222222")
        .put("walletSequence", sequence)
        .put("balanceAfter", balance)
        .put("schemaVersion", 1);
  }

  private static Stream<Arguments> invalidFields() {
    Stream<Arguments> identifiers =
        Stream.of("eventId", "walletId")
            .flatMap(
                field ->
                    Stream.of(null, "null", "\"invalid-uuid\"").map(v -> Arguments.of(field, v)));
    Stream<Arguments> numbers =
        Stream.of("schemaVersion", "walletSequence", "balanceAfter")
            .flatMap(
                field ->
                    Stream.of(
                            null,
                            "null",
                            "\"1\"",
                            "1.5",
                            "1.0",
                            "1e0",
                            "true",
                            "false",
                            "[]",
                            "{}",
                            "-1",
                            "-9223372036854775808",
                            "9223372036854775808",
                            "-9223372036854775809",
                            "18446744073709551616",
                            "18446744073709551617",
                            "27670116110564327423",
                            "-18446744073709551615")
                        .map(v -> Arguments.of(field, v)));
    Stream<Arguments> versions =
        Stream.of("0", "2", "4294967297", "-4294967295", "9223372036854775807")
            .map(v -> Arguments.of("schemaVersion", v));
    return Stream.of(identifiers, numbers, versions, Stream.of(Arguments.of("walletSequence", "0")))
        .flatMap(stream -> stream);
  }
}
