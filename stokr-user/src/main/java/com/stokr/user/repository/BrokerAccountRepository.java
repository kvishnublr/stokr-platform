package com.stokr.user.repository;

import com.stokr.user.domain.BrokerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrokerAccountRepository extends JpaRepository<BrokerAccount, UUID> {

    long countByUserIdAndDeletedFalse(UUID userId);

    boolean existsByUserIdAndDeletedFalse(UUID userId);

    Optional<BrokerAccount> findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
            UUID userId, String vendorCode);

    Optional<BrokerAccount> findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(UUID userId);

    List<BrokerAccount> findAllByVendorCodeIgnoreCaseAndDeletedFalse(String vendorCode);
}
