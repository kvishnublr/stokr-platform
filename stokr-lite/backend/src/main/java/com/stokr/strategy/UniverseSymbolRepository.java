package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UniverseSymbolRepository extends JpaRepository<UniverseSymbol, Long> {

    List<UniverseSymbol> findByGroupIdAndEnabledTrue(Long groupId);

    List<UniverseSymbol> findByGroupId(Long groupId);

    void deleteByGroupId(Long groupId);
}
