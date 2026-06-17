package com.stokr.chartink;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TraderConfigRepository extends JpaRepository<TraderConfig, Long> {

    Optional<TraderConfig> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
