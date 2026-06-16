package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyUniverseGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyUniverseGroupRepository extends JpaRepository<StrategyUniverseGroup, UUID> {

    Optional<StrategyUniverseGroup> findByGroupKey(String groupKey);

    List<StrategyUniverseGroup> findAllByEnabledTrue();

    List<StrategyUniverseGroup> findAllByAssetClassAndEnabledTrue(String assetClass);

    List<StrategyUniverseGroup> findAllBySegmentAndEnabledTrue(String segment);

    List<StrategyUniverseGroup> findAllByAutoManagedTrueAndEnabledTrue();

    Page<StrategyUniverseGroup> findAllByAssetClass(String assetClass, Pageable pageable);

    boolean existsByGroupKey(String groupKey);
}
