package com.familyfinance.market;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityCatalogStateStore {
    private final JdbcTemplate jdbc;

    public SecurityCatalogStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void ready(int count, java.time.Instant upstreamFetchedAt) {
        jdbc.update("update security_catalog_state set state='READY',item_count=?,updated_at=?,error=null where id=1",
                count, Timestamp.from(upstreamFetchedAt));
    }

    @Transactional
    public void failed(String error) {
        jdbc.update("update security_catalog_state set state='ERROR',error=? where id=1", safe(error));
    }

    public SecurityCatalogStatus status(boolean enabled) {
        if (!enabled) return new SecurityCatalogStatus("DISABLED", 0, null, null);
        return jdbc.queryForObject("select state,item_count,updated_at,error from security_catalog_state where id=1",
                (result, row) -> new SecurityCatalogStatus(
                        result.getString("state"), result.getInt("item_count"),
                        result.getTimestamp("updated_at") == null ? null : result.getTimestamp("updated_at").toInstant(),
                        result.getString("error")));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "MARKET_UPSTREAM_UNAVAILABLE";
        return value.length() <= 100 ? value : value.substring(0, 100);
    }
}
