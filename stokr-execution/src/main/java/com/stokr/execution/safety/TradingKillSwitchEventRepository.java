package com.stokr.execution.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingKillSwitchEventRepository extends JpaRepository<TradingKillSwitchEvent, Long> {

    Optional<TradingKillSwitchEvent> findFirstByOrderByCreatedAtDesc();
}
