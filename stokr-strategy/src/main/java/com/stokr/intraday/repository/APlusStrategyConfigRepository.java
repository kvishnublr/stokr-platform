package com.stokr.intraday.repository;

import com.stokr.intraday.domain.APlusStrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface APlusStrategyConfigRepository extends JpaRepository<APlusStrategyConfig, Long> {
    Optional<APlusStrategyConfig> findByIdAndEnabledTrue(Long id);
}
