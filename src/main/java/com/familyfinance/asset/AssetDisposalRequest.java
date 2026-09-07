package com.familyfinance.asset;
import java.time.LocalDate;
public record AssetDisposalRequest(LocalDate disposedOn,String proceeds,Long cashAccountId) {}
