package com.familyfinance.loan;
import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/loans/{id}")
public class LoanRepaymentPolicyController {
 private final LoanRepaymentPolicyService service;
 public LoanRepaymentPolicyController(LoanRepaymentPolicyService service){this.service=service;}
 @GetMapping("/repayment-policy") ApiEnvelope<LoanRepaymentPolicy> get(Authentication a,@PathVariable long id){return ApiEnvelope.data(service.get(a,id));}
 @PatchMapping("/repayment-policy") ApiEnvelope<LoanRepaymentPolicy> update(Authentication a,@PathVariable long id,@RequestBody LoanRepaymentPolicyRequest r){return ApiEnvelope.data(service.update(a,id,r));}
 @GetMapping("/term-options") ApiEnvelope<LoanRepaymentPolicyService.TermOptionsResponse> options(Authentication a,@PathVariable long id,
   @RequestParam String additionalPrincipal,@RequestParam LocalDate paidOn,@RequestParam(required=false) Long paymentAccountId){
  return ApiEnvelope.data(service.options(a,id,additionalPrincipal,paidOn,paymentAccountId));
 }
}
