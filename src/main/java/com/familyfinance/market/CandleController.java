package com.familyfinance.market;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/securities")
public class CandleController {
    private final CandleService candles;

    public CandleController(CandleService candles) {
        this.candles = candles;
    }

    @GetMapping("/{id}/candles")
    ApiEnvelope<CandleResponse> candles(
            Authentication authentication, @PathVariable long id,
            @RequestParam(defaultValue = "none") String adjust) {
        return ApiEnvelope.data(candles.candles(authentication, id, adjust));
    }
}
