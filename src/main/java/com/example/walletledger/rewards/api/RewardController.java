package com.example.walletledger.rewards.api;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.rewards.application.RewardService;
import com.example.walletledger.wallet.api.WalletController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class RewardController {
  private final RewardService rewards;
  private final CommandExecutor commands;

  public RewardController(RewardService rewards, CommandExecutor commands) {
    this.rewards = rewards;
    this.commands = commands;
  }

  public record ClaimRequest(@NotBlank @Size(max = 120) String completionReference) {}

  public record CompletionRequest(
      @NotNull UUID playerId,
      @NotNull UUID rewardId,
      @NotBlank @Size(max = 100) String source,
      @NotBlank @Size(max = 200) String reference) {}

  @PostMapping("/v1/daily-login/claims")
  @PreAuthorize("hasRole('PLAYER')")
  public ResponseEntity<?> daily(
      Authentication caller,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key) {
    UUID player = WalletController.player(caller);
    return execute(
        caller, key, "daily:" + player, Map.of(), () -> rewards.daily(player, caller.getName()));
  }

  @PostMapping("/v1/rewards/{rewardId}/claims")
  @PreAuthorize("hasRole('PLAYER')")
  public ResponseEntity<?> claim(
      Authentication caller,
      @PathVariable UUID rewardId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody ClaimRequest body) {
    UUID player = WalletController.player(caller);
    return execute(
        caller,
        key,
        "reward:" + player + ":" + rewardId,
        body,
        () -> rewards.claim(player, rewardId, body.completionReference(), caller.getName()));
  }

  @PostMapping("/v1/promotions/{promotionId}/claims")
  @PreAuthorize("hasRole('PLAYER')")
  public ResponseEntity<?> promotion(
      Authentication caller,
      @PathVariable UUID promotionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key) {
    UUID player = WalletController.player(caller);
    return execute(
        caller,
        key,
        "promotion:" + player + ":" + promotionId,
        Map.of(),
        () -> rewards.promotion(player, promotionId, caller.getName()));
  }

  @PostMapping("/internal/v1/action-completions")
  @PreAuthorize("hasRole('SERVICE')")
  public ResponseEntity<?> completion(
      Authentication caller,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody CompletionRequest body) {
    return execute(
        caller,
        key,
        "completion",
        body,
        () ->
            rewards.completion(
                body.playerId(),
                body.rewardId(),
                body.source(),
                body.reference(),
                caller.getName()));
  }

  private ResponseEntity<?> execute(
      Authentication caller,
      String key,
      String operation,
      Object payload,
      Supplier<Map<String, Object>> action) {
    var result = commands.execute(caller.getName(), key, operation, payload, action);
    return ResponseEntity.status(result.status())
        .contentType(
            result.status() >= 400
                ? MediaType.APPLICATION_PROBLEM_JSON
                : MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
