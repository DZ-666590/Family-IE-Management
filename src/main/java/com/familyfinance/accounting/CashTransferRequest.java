package com.familyfinance.accounting;

public record CashTransferRequest(Long fromAccountId,Long toAccountId,String amount,String occurredOn,String idempotencyKey) {}
