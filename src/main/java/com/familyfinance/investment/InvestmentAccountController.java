package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investment-accounts")
public class InvestmentAccountController {

    private final InvestmentAccountService accounts;
    private final com.familyfinance.accounting.AccountingCommandExecutor executor;

    public InvestmentAccountController(InvestmentAccountService accounts,com.familyfinance.accounting.AccountingCommandExecutor executor) {
        this.accounts = accounts;
        this.executor=executor;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<InvestmentAccountPage>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "ACTIVE") InvestmentAccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvestmentAccountPage result = accounts.list(authentication, status, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result));
    }

    @GetMapping("/{id}")
    ApiEnvelope<InvestmentAccountResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(accounts.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<InvestmentAccountResponse> create(
            Authentication authentication, @RequestBody InvestmentAccountCreateRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->accounts.create(authentication, request,key)));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<InvestmentAccountResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody InvestmentAccountPatchRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->accounts.update(authentication, id, request,key)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        executor.execute(()->{accounts.archive(authentication, id,key);return null;});
    }
}
