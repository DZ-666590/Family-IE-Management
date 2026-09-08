package com.familyfinance.market;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketPriceSnapshotRepository extends JpaRepository<MarketPriceSnapshot, Long> {
    Optional<MarketPriceSnapshot> findBySecurityIdAndTradeDate(Long securityId, LocalDate tradeDate);
    Optional<MarketPriceSnapshot> findBySecurityIdAndTradeDateAndSource(
            Long securityId, LocalDate tradeDate, String source);
    Optional<MarketPriceSnapshot> findFirstBySecurityIdOrderByTradeDateDescFetchedAtDescIdDesc(Long securityId);
    Optional<MarketPriceSnapshot> findFirstBySecurityIdAndTradeDateLessThanEqualOrderByTradeDateDescFetchedAtDescIdDesc(Long securityId,LocalDate day);
    List<MarketPriceSnapshot> findBySecurityIdInAndTradeDate(Collection<Long> securityIds, LocalDate tradeDate);
    List<MarketPriceSnapshot> findBySecurityIdInAndTradeDateAndSource(
            Collection<Long> securityIds, LocalDate tradeDate, String source);
}
