package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityCatalogImporter {
    private static final Pattern SYMBOL = Pattern.compile("^[0-9]{6}[.](SH|SZ|BJ)$");
    private static final int MIN_COMPLETE_ITEMS = 5000;
    private final SecurityRepository securities;
    private final SecurityCatalogStateStore states;

    public SecurityCatalogImporter(SecurityRepository securities, SecurityCatalogStateStore states) {
        this.securities = securities;
        this.states = states;
    }

    @Transactional
    public void publish(MarketDirectoryResponse response) {
        List<MarketDirectoryItem> items = response == null ? null : response.items();
        if (items == null || items.size() < MIN_COMPLETE_ITEMS || response.fetchedAt() == null) invalid();
        Map<String, MarketDirectoryItem> verified = new HashMap<>();
        Set<String> markets = new HashSet<>();
        for (MarketDirectoryItem item : items) {
            if (item == null || item.tsCode() == null || !SYMBOL.matcher(item.tsCode()).matches()
                    || item.market() == null || !item.tsCode().endsWith("." + item.market())
                    || item.name() == null || item.name().isBlank() || item.name().length() > 100
                    || verified.putIfAbsent(item.tsCode(), item) != null) invalid();
            markets.add(item.market());
        }
        if (!markets.containsAll(Set.of("SH", "SZ", "BJ"))) invalid();
        Map<String, Security> existing = new HashMap<>();
        for (Security security : securities.findAll()) existing.put(security.getTsCode(), security);
        Set<String> seen = new HashSet<>();
        for (MarketDirectoryItem item : verified.values()) {
            Security security = existing.get(item.tsCode());
            if (security == null) security = new Security(item.market(), item.tsCode(), item.name().trim());
            else security.publishCatalog(item.name().trim());
            securities.save(security);
            seen.add(item.tsCode());
        }
        existing.values().stream().filter(Security::isCatalogVerified)
                .filter(security -> !seen.contains(security.getTsCode()))
                .forEach(Security::retireFromCatalog);
        securities.flush();
        states.ready(verified.size());
    }

    private static void invalid() {
        throw new MarketProviderException("MARKET_DIRECTORY_INVALID", false);
    }
}
