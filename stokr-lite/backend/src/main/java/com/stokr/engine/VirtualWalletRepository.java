package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface VirtualWalletRepository extends JpaRepository<VirtualWallet, Long> {
    Optional<VirtualWallet> findByUserId(Long userId);
    Optional<VirtualWallet> findByUserIdAndActiveTrue(Long userId);
}
