package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UniverseSymbolRepository extends JpaRepository<UniverseSymbol, Long> {

    List<UniverseSymbol> findByGroupIdAndEnabledTrue(Long groupId);

    List<UniverseSymbol> findByGroupId(Long groupId);

    @Modifying
    @Query("delete from UniverseSymbol u where u.groupId = :groupId")
    void deleteByGroupId(Long groupId);
}
