package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class CashTransferController {
    private final CashTransferService service;
    private final AccountingCommandExecutor executor;
    public CashTransferController(CashTransferService service,AccountingCommandExecutor executor) {this.service=service;this.executor=executor;}
    @GetMapping
    ApiEnvelope<List<CashTransferResponse>> list(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiEnvelope.data(service.list(auth,page,size));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<CashTransferResponse> create(Authentication auth,@RequestBody CashTransferRequest request) {
        var frozen=new CashTransferRequest(request.fromAccountId(),request.toAccountId(),request.amount(),request.occurredOn(),AccountingRequests.key(request.idempotencyKey()));
        return ApiEnvelope.data(executor.execute(()->service.create(auth,frozen)));
    }
}
