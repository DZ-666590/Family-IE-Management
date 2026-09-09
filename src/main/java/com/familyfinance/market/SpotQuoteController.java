package com.familyfinance.market;

import com.familyfinance.reporting.*;
import com.familyfinance.shared.*;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class SpotQuoteController {
    private final SpotQuoteService quotes;
    private final PortfolioService portfolios;
    private final CurrentHousehold household;
    public SpotQuoteController(SpotQuoteService quotes,PortfolioService portfolios,CurrentHousehold household){this.quotes=quotes;this.portfolios=portfolios;this.household=household;}
    public record Quotes(List<SpotQuoteService.Quote> quotes,int nextRefreshSeconds){}
    public record LivePortfolio(PortfolioResponse portfolio,List<SpotQuoteService.Quote> quotes,int nextRefreshSeconds,boolean partial){}
    @GetMapping("/api/market-quotes/live")
    public ApiEnvelope<Quotes> quotes(Authentication auth,@RequestParam List<Long> securityIds){household.id(auth);var result=quotes.fetch(securityIds);return ApiEnvelope.data(new Quotes(result.quotes(),result.nextRefreshSeconds()));}
    @GetMapping("/api/portfolio/live")
    public ApiEnvelope<LivePortfolio> portfolio(Authentication auth){
        long id=household.id(auth);
        var baseline=portfolios.portfolio(id);
        var held=baseline.positions().stream().filter(p->p.quantity().signum()>0).map(PortfolioPositionResponse::securityId).distinct().toList();
        var result=quotes.fetch(held.stream().limit(150).toList());
        var projected=portfolios.portfolioWithQuotes(id,result.prices());
        boolean fallback=projected.positions().stream().anyMatch(p->p.quantity().signum()>0&&p.source()!=QuoteSource.TENCENT&&p.source()!=QuoteSource.MANUAL);
        return ApiEnvelope.data(new LivePortfolio(projected,result.quotes(),result.nextRefreshSeconds(),held.size()>150||fallback));
    }
}
