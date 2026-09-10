package com.example.walletledger.wallet.api;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.wallet.application.WalletService;
import com.example.walletledger.wallet.domain.BusinessException;
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
@RequestMapping("/v1")
public class WalletController {
  private final WalletService wallets;
  private final CommandExecutor commands;

  public WalletController(WalletService wallets, CommandExecutor commands) {
    this.wallets = wallets;
    this.commands = commands;
  }

  public record PlayerRequest(@NotNull UUID playerId) {}

  public record MoneyRequest(
      @NotNull @Positive Long amount,
      @NotBlank @Size(max = 500) String reason,
      @NotBlank @Size(max = 100) String source,
      @NotBlank @Size(max = 200) String reference) {}

  public record TransferRequest(
      @NotNull UUID recipientId,
      @NotNull @Positive Long amount,
      @NotBlank @Size(max = 500) String reason,
      @NotBlank @Size(max = 100) String source,
      @NotBlank @Size(max = 200) String reference) {}

  public record RefundRequest(
      @NotBlank @Size(max = 500) String reason,
      @NotBlank @Size(max = 100) String source,
      @NotBlank @Size(max = 200) String reference) {}

  @PostMapping("/players")
  @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
  public ResponseEntity<?> provision(
      Authentication caller,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody PlayerRequest body) {
    return execute(caller, key, "provision", body, () -> wallets.provision(body.playerId()));
  }

  @PostMapping("/wallets/{playerId}/credits")
  @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
  public ResponseEntity<?> credit(
      Authentication caller,
      @PathVariable UUID playerId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody MoneyRequest body) {
    return execute(
        caller,
        key,
        "credit:" + playerId,
        body,
        () ->
            wallets.credit(
                playerId,
                body.amount(),
                caller.getName(),
                body.reason(),
                body.source(),
                body.reference()));
  }

  @PostMapping("/wallets/{playerId}/debits")
  @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
  public ResponseEntity<?> debit(
      Authentication caller,
      @PathVariable UUID playerId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody MoneyRequest body) {
    return execute(
        caller,
        key,
        "debit:" + playerId,
        body,
        () ->
            wallets.debit(
                playerId,
                body.amount(),
                caller.getName(),
                body.reason(),
                body.source(),
                body.reference()));
  }

  @GetMapping("/wallets/{playerId}/balance")
  @PreAuthorize(
      "hasAnyRole('SERVICE','ADMIN') or (hasRole('PLAYER') and authentication.name == #playerId.toString())")
  public Map<String, Object> balance(@PathVariable UUID playerId) {
    return wallets.balance(playerId);
  }

  @GetMapping("/wallets/{playerId}/transactions")
  @PreAuthorize(
      "hasAnyRole('SERVICE','ADMIN') or (hasRole('PLAYER') and authentication.name == #playerId.toString())")
  public Map<String, Object> history(
      @PathVariable UUID playerId,
      @RequestParam(required = false) @Positive Long cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return wallets.history(playerId, cursor, limit);
  }

  @PostMapping("/transfers")
  @PreAuthorize("hasRole('PLAYER')")
  public ResponseEntity<?> transfer(
      Authentication caller,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody TransferRequest body) {
    UUID sender = player(caller);
    var result =
        commands.execute(
            caller.getName(),
            key,
            "transfer:" + sender,
            body,
            () ->
                wallets.transfer(
                    sender,
                    body.recipientId(),
                    body.amount(),
                    caller.getName(),
                    body.reason(),
                    body.source(),
                    body.reference()));
    return response(TransferReceipt.forPlayer(result));
  }

  @PostMapping("/transactions/{transactionId}/refunds")
  @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
  public ResponseEntity<?> refund(
      Authentication caller,
      @PathVariable UUID transactionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody RefundRequest body) {
    return execute(
        caller,
        key,
        "refund:" + transactionId,
        body,
        () ->
            wallets.refund(
                transactionId, caller.getName(), body.reason(), body.source(), body.reference()));
  }

  @GetMapping("/admin/reconciliation")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> reconciliation() {
    return wallets.reconciliation();
  }

  private ResponseEntity<?> execute(
      Authentication caller,
      String key,
      String operation,
      Object payload,
      Supplier<Map<String, Object>> action) {
    var result = commands.execute(caller.getName(), key, operation, payload, action);
    return response(result);
  }

  private ResponseEntity<?> response(CommandResult result) {
    return ResponseEntity.status(result.status())
        .contentType(
            result.status() >= 400
                ? MediaType.APPLICATION_PROBLEM_JSON
                : MediaType.APPLICATION_JSON)
        .body(result.body());
  }

  public static UUID player(Authentication caller) {
    try {
      return UUID.fromString(caller.getName());
    } catch (IllegalArgumentException invalid) {
      throw new BusinessException(403, "INVALID_PLAYER_IDENTITY", "Player subject must be a UUID");
    }
  }
}
