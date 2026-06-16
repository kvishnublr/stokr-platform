package com.stokr.risk.repository;

import com.stokr.risk.domain.RiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RiskEventRepository extends JpaRepository<RiskEvent, UUID> {
}
