package com.example.walletledger.wallet.domain;

public final class BusinessException extends RuntimeException {
  private final int status;
  private final String code;

  public BusinessException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
