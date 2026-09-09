package com.familyfinance.market;
import java.util.List;
public record SpotBatchResponse(List<SpotQuote> quotes,String marketState,int nextRefreshSeconds) {}
