package com.familyfinance.accounting;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class CurrencyCapabilitiesController {
 private final MultiCurrencyPolicy policy;private final CurrentMembership membership;private final DeploymentRevisionGate deployment;
 public CurrencyCapabilitiesController(MultiCurrencyPolicy policy,CurrentMembership membership,DeploymentRevisionGate deployment){this.policy=policy;this.membership=membership;this.deployment=deployment;}
 public record Capabilities(List<String> currencies,boolean deploymentReady){}
 @GetMapping("/api/currencies") public ApiEnvelope<Capabilities> get(Authentication auth){membership.require(auth);return ApiEnvelope.data(new Capabilities(policy.currencies(),deployment.ready()));}
}
