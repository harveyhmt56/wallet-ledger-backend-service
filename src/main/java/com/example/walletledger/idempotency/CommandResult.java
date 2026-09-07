package com.example.walletledger.idempotency;

import com.fasterxml.jackson.databind.JsonNode;

public record CommandResult(int status, JsonNode body) {}
