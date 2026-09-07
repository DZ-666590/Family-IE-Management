package com.familyfinance.accounting;

import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Global catalogue existence checks need current reads without cross-household exclusive lock cycles. */
@Component
class LedgerSecurityCatalog {
    private final JdbcTemplate jdbc;
    private final boolean mysql;

    LedgerSecurityCatalog(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        String product=jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if(!"MySQL".equals(product) && !"H2".equals(product))
            throw new IllegalStateException("Unsupported accounting database dialect");
        mysql="MySQL".equals(product);
    }

    Set<Long> lockCurrent(Set<Long> requestedIds) {
        if(requestedIds.isEmpty()) return Set.of();
        if(!mysql) {
            // H2 is test-only and has no shared current-read SQL syntax. Serializing its
            // entire catalogue keeps a stable transaction-wide lock order across posts.
            return new HashSet<>(jdbc.queryForList("select id from securities order by id for update",Long.class));
        }
        Set<Long> existing=new HashSet<>();
        for(long id:requestedIds) {
            // Compatible S locks stay current under MySQL RR and remain safe when two
            // households encounter the same securities in opposite orders across posts.
            existing.addAll(jdbc.queryForList("select id from securities where id=? lock in share mode",Long.class,id));
        }
        return existing;
    }
}
