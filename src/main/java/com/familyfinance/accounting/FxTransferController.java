package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/fx-transfers")
public class FxTransferController {
    private final FxTransferService service;private final AccountingCommandExecutor executor;
    public FxTransferController(FxTransferService service,AccountingCommandExecutor executor){this.service=service;this.executor=executor;}
    @GetMapping public ApiEnvelope<FxTransferService.Page> list(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiEnvelope.data(service.list(auth,page,size));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<FxTransferResponse> create(Authentication auth,@RequestBody FxTransferRequest request){
        var frozen=freeze(request);return ApiEnvelope.data(executor.execute(()->service.save(auth,null,frozen)));
    }
    @PatchMapping("/{id}") public ApiEnvelope<FxTransferResponse> update(Authentication auth,@PathVariable long id,@RequestBody FxTransferRequest request){
        var frozen=freeze(request);return ApiEnvelope.data(executor.execute(()->service.save(auth,id,frozen)));
    }
    @DeleteMapping("/{id}") public ApiEnvelope<FxTransferResponse> reverse(Authentication auth,@PathVariable long id,@RequestParam long expectedRevision,@RequestHeader(value="Idempotency-Key",required=false) String key){
        String frozen=AccountingRequests.key(key);return ApiEnvelope.data(executor.execute(()->service.reverse(auth,id,expectedRevision,frozen)));
    }
    private static FxTransferRequest freeze(FxTransferRequest r){return new FxTransferRequest(r.fromAccountId(),r.toAccountId(),r.fromAmount(),r.toAmount(),r.fee(),r.occurredOn(),AccountingRequests.key(r.idempotencyKey()),r.expectedRevision());}
}
