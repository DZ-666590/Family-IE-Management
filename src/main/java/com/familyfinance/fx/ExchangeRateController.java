package com.familyfinance.fx;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyPermissionService;
import com.familyfinance.shared.ApiEnvelope;
import java.time.LocalDate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {
    private final ExchangeRateService rates;
    private final CurrentMembership membership;
    private final FamilyPermissionService permissions;
    private final FxJournalRates journals;
    public ExchangeRateController(ExchangeRateService rates,CurrentMembership membership,FamilyPermissionService permissions,FxJournalRates journals){this.rates=rates;this.membership=membership;this.permissions=permissions;this.journals=journals;}
    public record Audit(long unverifiedReferences){}
    @GetMapping("/audit") public ApiEnvelope<Audit> audit(Authentication auth){
        var current=membership.require(auth);
        return ApiEnvelope.data(new Audit(journals.unverifiedReferences(current.householdId())));
    }
    @GetMapping public ApiEnvelope<ExchangeRateService.Table> table(Authentication auth,@RequestParam(required=false) LocalDate asOf){
        membership.require(auth);return ApiEnvelope.data(rates.table(asOf));
    }
    @GetMapping("/history") public ApiEnvelope<ExchangeRateService.History> history(Authentication auth,@RequestParam LocalDate from,@RequestParam LocalDate to){
        membership.require(auth);return ApiEnvelope.data(rates.historyView(from,to));
    }
    @PostMapping("/refresh") public ApiEnvelope<ExchangeRateService.Table> refresh(Authentication auth,@RequestParam(required=false) LocalDate asOf){
        permissions.requireAdmin(membership.require(auth));return ApiEnvelope.data(rates.refresh(asOf));
    }
}
