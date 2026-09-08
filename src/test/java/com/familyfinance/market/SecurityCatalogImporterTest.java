package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = {"app.seed.enabled=false", "app.scheduling.enabled=false"})
@Transactional
class SecurityCatalogImporterTest {
    @Autowired SecurityCatalogImporter importer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void validCompleteCatalogPreservesSharedIdsAndRetiresOnlyMissingVerifiedRows() {
        jdbc.update("""
                insert into securities (market,ts_code,name,security_type,active,catalog_verified)
                values ('SH','600000.SH','旧名称','STOCK',true,false),
                       ('BJ','920002.BJ','已退目录','STOCK',true,true)
                """);
        long existingId = jdbc.queryForObject(
                "select id from securities where ts_code='600000.SH'", Long.class);
        List<MarketDirectoryItem> items = directoryItems();
        items.set(0, new MarketDirectoryItem("600000.SH", "浦发银行", "SH"));

        importer.publish(new MarketDirectoryResponse(items, Instant.parse("2026-09-08T08:00:00Z"), false));

        assertThat(jdbc.queryForObject(
                "select id from securities where ts_code='600000.SH'", Long.class)).isEqualTo(existingId);
        assertThat(jdbc.queryForObject(
                "select concat(name,'|',active,'|',catalog_verified) from securities where ts_code='600000.SH'",
                String.class)).isEqualTo("浦发银行|TRUE|TRUE");
        assertThat(jdbc.queryForObject(
                "select concat(active,'|',catalog_verified) from securities where ts_code='920002.BJ'",
                String.class)).isEqualTo("FALSE|FALSE");
        assertThat(jdbc.queryForObject(
                "select concat(state,'|',item_count) from security_catalog_state where id=1", String.class))
                .isEqualTo("READY|5000");
    }

    @Test
    void incompleteCatalogCannotOverwritePublishedReferences() {
        jdbc.update("""
                insert into securities (market,ts_code,name,security_type,active,catalog_verified)
                values ('SH','600000.SH','浦发银行','STOCK',true,true)
                """);
        assertThatThrownBy(() -> importer.publish(new MarketDirectoryResponse(
                List.of(new MarketDirectoryItem("600000.SH", "伪名称", "SH")), Instant.now(), false)))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_DIRECTORY_INVALID");
        assertThat(jdbc.queryForObject(
                "select concat(name,'|',active,'|',catalog_verified) from securities where ts_code='600000.SH'",
                String.class)).isEqualTo("浦发银行|TRUE|TRUE");
    }

    private static List<MarketDirectoryItem> directoryItems() {
        List<MarketDirectoryItem> result = new ArrayList<>();
        for (int code = 600000; code < 602500; code++) {
            result.add(new MarketDirectoryItem(code + ".SH", "目录证券" + code, "SH"));
        }
        for (int code = 1; code <= 2400; code++) {
            result.add(new MarketDirectoryItem("%06d.SZ".formatted(code), "目录证券" + code, "SZ"));
        }
        for (int code = 900000; code < 900100; code++) {
            result.add(new MarketDirectoryItem(code + ".BJ", "目录证券" + code, "BJ"));
        }
        return result;
    }
}
