package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findByUserId(Long userId);
    List<Deployment> findByUserIdAndEnabledTrue(Long userId);
    List<Deployment> findByUserIdAndStatus(Long userId, String status);
    List<Deployment> findByStatus(String status);
    java.util.Optional<Deployment> findByIdAndUserId(Long id, Long userId);
    long countByStrategyIdAndEnabledTrueAndIsLiveTrue(Long strategyId);

    @Query(value = "SELECT w.* FROM virtual_wallets w ORDER BY w.total_pnl DESC LIMIT :limit", nativeQuery = true)
    List<VirtualWallet> findAllVirtualWalletsByPnl(int limit);
}
