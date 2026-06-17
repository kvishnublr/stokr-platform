package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UniverseGroupRepository extends JpaRepository<UniverseGroup, Long> {

    Optional<UniverseGroup> findByGroupKey(String groupKey);

    List<UniverseGroup> findByEnabledTrue();

    List<UniverseGroup> findByAutoManagedTrueAndEnabledTrue();

    boolean existsByGroupKey(String groupKey);
}
