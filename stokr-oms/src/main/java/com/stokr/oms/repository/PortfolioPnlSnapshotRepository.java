package com.stokr.oms.repository;

import com.stokr.oms.domain.PortfolioPnlSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PortfolioPnlSnapshotRepository extends JpaRepository<PortfolioPnlSnapshot, UUID> {

    List<PortfolioPnlSnapshot> findTop200ByUserIdAndDeletedFalseOrderByAsOfDesc(UUID userId);
}
