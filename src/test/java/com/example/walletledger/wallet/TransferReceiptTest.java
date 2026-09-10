package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.idempotency.CommandResult;
import com.example.walletledger.wallet.api.WalletController;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class TransferReceiptTest {
  private final ObjectMapper json = new ObjectMapper();
  private final CommandExecutor commands = mock(CommandExecutor.class);
  private final WalletController controller =
      new WalletController(mock(WalletService.class), commands);

  @Test
  void exposesOnlySenderReceiptFieldsWithoutMutatingStoredResult() throws Exception {
    var stored =
        json.readTree(
            """
        {"transactionId":"tx","operation":"TRANSFER","amount":10,"reason":"gift",
         "occurredAt":"2026-09-10T00:00:00Z","playerId":"sender","walletId":"sender",
         "balanceAfter":90,"walletSequence":2,"recipientId":"recipient",
         "recipientBalanceAfter":12345,"futurePrivateProperty":{"balance":9876}}
        """);
    var original = stored.deepCopy();
    when(commands.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(new CommandResult(200, stored));

    var response = transfer();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(response.getBody()))
        .isEqualTo(
            json.readTree(
                """
        {"transactionId":"tx","operation":"TRANSFER","amount":10,"reason":"gift",
         "occurredAt":"2026-09-10T00:00:00Z","playerId":"sender","walletId":"sender",
         "balanceAfter":90,"walletSequence":2,"recipientId":"recipient"}
        """));
    assertThat(stored).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 409})
  void preservesRejectedCommandStatusAndProblemBody(int status) throws Exception {
    var problem =
        json.readTree(
            """
        {"type":"about:blank","status":409,"title":"INSUFFICIENT_FUNDS",
         "code":"INSUFFICIENT_FUNDS","detail":"Insufficient funds"}
        """);
    when(commands.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(new CommandResult(status, problem));

    var response = transfer();

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(response.getBody()))
        .isEqualTo(problem);
  }

  private org.springframework.http.ResponseEntity<?> transfer() {
    var caller = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "unused");
    return controller.transfer(
        caller,
        "key",
        new WalletController.TransferRequest(UUID.randomUUID(), 10L, "gift", "test", "reference"));
  }
}
