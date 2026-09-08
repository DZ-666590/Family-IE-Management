package com.familyfinance.fx;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyPermissionService;
import com.familyfinance.shared.ApiEnvelope;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {
    private final ExchangeRateService rates;
    private final CurrentMembership membership;
    private final FamilyPermissionService permissions;
    public ExchangeRateController(ExchangeRateService rates,CurrentMembership membership,FamilyPermissionService permissions){this.rates=rates;this.membership=membership;this.permissions=permissions;}
    @GetMapping public ApiEnvelope<ExchangeRateService.Table> table(Authentication auth,@RequestParam(required=false) LocalDate asOf){
        membership.require(auth);return ApiEnvelope.data(rates.table(asOf));
    }
    @GetMapping("/history") public ApiEnvelope<List<ExchangeRateService.Row>> history(Authentication auth,@RequestParam LocalDate from,@RequestParam LocalDate to){
        membership.require(auth);return ApiEnvelope.data(rates.history(from,to));
    }
    @PostMapping("/refresh") public ApiEnvelope<ExchangeRateService.Table> refresh(Authentication auth,@RequestParam(required=false) LocalDate asOf){
        permissions.requireAdmin(membership.require(auth));return ApiEnvelope.data(rates.refresh(asOf));
    }
}
