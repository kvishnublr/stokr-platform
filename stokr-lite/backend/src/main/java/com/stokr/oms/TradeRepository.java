package com.stokr.oms;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByOrderId(Long orderId);
}
