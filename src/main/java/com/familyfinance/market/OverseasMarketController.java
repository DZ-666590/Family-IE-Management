package com.familyfinance.market;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overseas-market")
public class OverseasMarketController {
    private final OverseasMarketService market;

    public OverseasMarketController(OverseasMarketService market) {
        this.market = market;
    }

    @GetMapping("/search")
    ApiEnvelope<OverseasSearchResponse> search(
            Authentication authentication,
            @RequestParam String market,
            @RequestParam(defaultValue = "") String q) {
        return ApiEnvelope.data(this.market.search(authentication, market, q));
    }

    @GetMapping("/candles")
    ApiEnvelope<OverseasCandleResponse> candles(
            Authentication authentication,
            @RequestParam String market,
            @RequestParam String symbol) {
        return ApiEnvelope.data(this.market.candles(authentication, market, symbol));
    }
}
