package com.familyfinance.loan;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.accounting.*;
import java.time.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/loan-installments")
public class LoanInstallmentController {
 private final LoanInstallmentConfirmationService confirmations;
 private final AccountingCommandExecutor executor;private final Clock clock;
 LoanInstallmentController(LoanInstallmentConfirmationService confirmations,AccountingCommandExecutor executor,Clock clock){this.confirmations=confirmations;this.executor=executor;this.clock=clock;}
 @PostMapping("/{id}/confirm") ApiEnvelope<LoanInstallmentResponse> confirm(Authentication authentication,@PathVariable long id,@RequestBody(required=false) LoanPaymentRequest body,@RequestHeader(value="Idempotency-Key",required=false) String supplied){
  String key=AccountingRequests.key(supplied);LoanPaymentRequest request=body==null?new LoanPaymentRequest(null):body;LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
  return ApiEnvelope.data(executor.execute(()->confirmations.confirm(authentication,id,request,today,key)));
 }
}
