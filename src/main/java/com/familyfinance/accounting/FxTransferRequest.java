package com.familyfinance.accounting;

public record FxTransferRequest(Long fromAccountId,Long toAccountId,String fromAmount,String toAmount,
        String fee,String occurredOn,String idempotencyKey,Long expectedRevision) {}
