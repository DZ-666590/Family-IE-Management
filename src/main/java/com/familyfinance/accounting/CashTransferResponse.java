package com.familyfinance.accounting;

import java.time.LocalDate;
public record CashTransferResponse(long id,long fromAccountId,long toAccountId,String amount,LocalDate occurredOn,long actorId) {}
