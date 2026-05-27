package com.stokr.marketdata.integrity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDataIntegrityRejectionRepository extends JpaRepository<MarketDataIntegrityRejection, Long> {
}
