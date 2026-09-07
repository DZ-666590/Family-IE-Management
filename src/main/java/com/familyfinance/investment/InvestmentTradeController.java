package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
import java.time.LocalDate;
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
@RequestMapping("/api/investment-trades")
public class InvestmentTradeController {

    private final InvestmentTradeService trades;
    private final com.familyfinance.accounting.AccountingCommandExecutor executor;

    public InvestmentTradeController(InvestmentTradeService trades,com.familyfinance.accounting.AccountingCommandExecutor executor) {
        this.trades = trades;
        this.executor=executor;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<InvestmentTradePage>> list(
            Authentication authentication,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long securityId,
            @RequestParam(required = false) InvestmentTradeType type,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvestmentTradePage result = trades.list(
                authentication, accountId, securityId, type, from, to, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result));
    }

    @GetMapping("/{id}")
    ApiEnvelope<InvestmentTradeResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(trades.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<InvestmentTradeMutationResponse> create(
            Authentication authentication, @RequestBody InvestmentTradeRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->trades.create(authentication, request,key)));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<InvestmentTradeMutationResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody InvestmentTradePatchRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->trades.update(authentication, id, request,key)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id,
            @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        String key=com.familyfinance.accounting.AccountingRequests.key(supplied);
        executor.execute(()->{trades.delete(authentication, id,key);return null;});
    }
}
