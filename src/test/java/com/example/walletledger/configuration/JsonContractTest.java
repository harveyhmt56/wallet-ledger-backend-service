package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class JsonContractTest {
  record Amount(Long amount) {}

  @Test
  void acceptsWholeUnitsAndRejectsFractionsCoercionsAndOverflow() throws Exception {
    var builder = new Jackson2ObjectMapperBuilder();
    new CoreConfiguration().strictNumbers().customize(builder);
    ObjectMapper json = builder.build();
    assertThat(json.readValue("{\"amount\":1}", Amount.class).amount()).isEqualTo(1);
    for (String input : new String[] {"1.5", "\"10\"", "9223372036854775808"}) {
      assertThatThrownBy(() -> json.readValue("{\"amount\":" + input + "}", Amount.class))
          .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
  }
}
