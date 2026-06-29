package com.stokr.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface NseDeliveryDataRepository extends JpaRepository<NseDeliveryData, Long> {

    List<NseDeliveryData> findByTradeDate(LocalDate tradeDate);

    Optional<NseDeliveryData> findByTradeDateAndSymbol(LocalDate tradeDate, String symbol);

    @Query("SELECT n FROM NseDeliveryData n WHERE n.tradeDate = :tradeDate AND n.delivPct >= :minPct AND n.series = 'EQ' ORDER BY n.delivPct DESC")
    List<NseDeliveryData> findDeliveryLeaders(LocalDate tradeDate, BigDecimal minPct);

    @Query("SELECT MAX(n.tradeDate) FROM NseDeliveryData n")
    Optional<LocalDate> findLatestDate();

    boolean existsByTradeDate(LocalDate tradeDate);

    @Query("SELECT n FROM NseDeliveryData n WHERE n.symbol = :symbol ORDER BY n.tradeDate DESC")
    List<NseDeliveryData> findBySymbolOrderByDateDesc(String symbol);
}
