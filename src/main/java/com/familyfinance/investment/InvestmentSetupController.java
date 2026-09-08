package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investment-setup")
public class InvestmentSetupController {
    private final InvestmentSetupService setup;

    public InvestmentSetupController(InvestmentSetupService setup) {
        this.setup = setup;
    }

    @GetMapping
    ApiEnvelope<InvestmentSetupStatus> status(Authentication authentication) {
        return ApiEnvelope.data(setup.status(authentication));
    }

    @PostMapping("/complete")
    ApiEnvelope<InvestmentSetupStatus> complete(Authentication authentication) {
        return ApiEnvelope.data(setup.complete(authentication));
    }
}
