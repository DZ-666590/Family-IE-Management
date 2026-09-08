package com.familyfinance.accounting;

import java.time.LocalDate;

public record FxTransferResponse(long id,long fromAccountId,long toAccountId,String fromCurrency,String toCurrency,
        String fromAmount,String toAmount,String fee,String actualRate,LocalDate occurredOn,long revision,boolean reversed) {}
