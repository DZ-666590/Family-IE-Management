package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.ResourceNotFoundException;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CandleService {
    private final SecurityRepository securities;
    private final CurrentHousehold currentHousehold;
    private final MarketDataClient client;

    public CandleService(
            SecurityRepository securities, CurrentHousehold currentHousehold, MarketDataClient client) {
        this.securities = securities;
        this.currentHousehold = currentHousehold;
        this.client = client;
    }

    public CandleResponse candles(Authentication authentication, long id, String rawAdjustment) {
        currentHousehold.id(authentication);
        String adjustment = rawAdjustment == null ? "" : rawAdjustment.trim().toLowerCase(Locale.ROOT);
        if (!adjustment.equals("none") && !adjustment.equals("qfq")) {
            throw new MarketValidationException(java.util.Map.of("adjust", "复权方式只能是 none 或 qfq"));
        }
        Security security = securities.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("证券不存在"));
        if (!security.isCatalogVerified() || !security.isActive() || security.getMarket().equals("BJ")) {
            return CandleResponse.unsupported(security.getTsCode(), adjustment);
        }
        CandleResponse response = client.candles(security.getTsCode(), adjustment);
        BaoStockQuoteProvider.validate(response, security.getTsCode(), adjustment);
        return response;
    }
}
