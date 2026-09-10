package com.example.walletledger.wallet.api;

import com.example.walletledger.idempotency.CommandResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** The public sender view, applied after both execution and stored idempotency replay. */
public final class TransferReceipt {
  private static final List<String> PUBLIC_FIELDS =
      List.of(
          "transactionId",
          "operation",
          "amount",
          "reason",
          "occurredAt",
          "playerId",
          "walletId",
          "balanceAfter",
          "walletSequence",
          "recipientId");

  private TransferReceipt() {}

  public static CommandResult forPlayer(CommandResult result) {
    if (result.status() >= 400) {
      return result;
    }
    ObjectNode receipt = ((ObjectNode) result.body()).deepCopy();
    receipt.retain(PUBLIC_FIELDS);
    return new CommandResult(result.status(), receipt);
  }
}
