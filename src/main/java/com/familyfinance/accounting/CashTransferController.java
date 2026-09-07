package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class CashTransferController {
    private final CashTransferService service;
    private final AccountingCommandExecutor executor;
    public CashTransferController(CashTransferService service,AccountingCommandExecutor executor) {this.service=service;this.executor=executor;}
    @GetMapping
    ResponseEntity<ApiEnvelope<List<CashTransferResponse>>> list(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var result=service.page(auth,page,size);
        return ResponseEntity.ok().header("X-Page",String.valueOf(result.page()))
            .header("X-Page-Size",String.valueOf(result.size())).header("X-Total-Elements",String.valueOf(result.totalElements()))
            .header("X-Total-Pages",String.valueOf(result.totalPages())).header("X-Has-Next",String.valueOf(result.hasNext()))
            .body(ApiEnvelope.data(result.items()));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<CashTransferResponse> create(Authentication auth,@RequestBody CashTransferRequest request) {
        var frozen=new CashTransferRequest(request.fromAccountId(),request.toAccountId(),request.amount(),request.occurredOn(),AccountingRequests.key(request.idempotencyKey()));
        return ApiEnvelope.data(executor.execute(()->service.create(auth,frozen)));
    }
}
