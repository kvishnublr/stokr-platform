package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledTradeRepository extends JpaRepository<ScheduledTrade, Long> {
    List<ScheduledTrade> findByStatusAndScheduledTimeLessThanEqual(String status, LocalDateTime time);
}
