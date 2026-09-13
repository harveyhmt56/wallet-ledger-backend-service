package com.example.walletledger.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.rewards.api.RewardController;
import com.example.walletledger.rewards.application.RewardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RewardControllerTest {
  @ParameterizedTest
  @CsvSource({
    "200,application/json",
    "400,application/problem+json",
    "409,application/problem+json"
  })
  void rewardClaimPreservesCommandStatusBodyAndMatchingMediaType(int status, String mediaType)
      throws Exception {
    var json = new ObjectMapper();
    var commands = mock(CommandExecutor.class);
    var rewards = mock(RewardService.class);
    var http = MockMvcBuilders.standaloneSetup(new RewardController(rewards, commands)).build();
    UUID player = UUID.randomUUID();
    UUID reward = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    var caller =
        UsernamePasswordAuthenticationToken.authenticated(
            player.toString(), "unused", List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
    var body = json.createObjectNode();
    if (status == 200) {
      body.put("transactionId", UUID.randomUUID().toString()).put("amount", 100);
    } else {
      String code = status == 400 ? "INVALID_COMPLETION_REFERENCE" : "REWARD_ALREADY_CLAIMED";
      body.put("type", "about:blank")
          .put("status", status)
          .put("code", code)
          .put("title", code)
          .put("detail", "Reward claim rejected");
    }
    when(commands.execute(
            eq(player.toString()),
            eq(key),
            eq("reward:" + player + ":" + reward),
            any(RewardController.ClaimRequest.class),
            any()))
        .thenReturn(new CommandResult(status, body));

    var response =
        http.perform(
                post("/v1/rewards/{rewardId}/claims", reward)
                    .principal(caller)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"completionReference\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().is(status))
            .andExpect(content().contentType(mediaType))
            .andReturn()
            .getResponse();

    assertThat(json.readTree(response.getContentAsString())).isEqualTo(body);
    verify(commands)
        .execute(
            eq(player.toString()),
            eq(key),
            eq("reward:" + player + ":" + reward),
            any(RewardController.ClaimRequest.class),
            any());
  }
}
