package com.stokr.core.repository;

import com.stokr.core.domain.BrokerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrokerAccountRepository extends JpaRepository<BrokerAccount, UUID> {

    Optional<BrokerAccount> findByIdAndDeletedFalse(UUID id);

    List<BrokerAccount> findByUserIdAndDeletedFalse(UUID userId);

    Optional<BrokerAccount> findByUserIdAndVendorCodeAndDeletedFalse(UUID userId, String vendorCode);

    Optional<BrokerAccount> findByUserIdAndIsActiveTrueAndDeletedFalse(UUID userId);

    List<BrokerAccount> findByOrganizationIdAndDeletedFalse(UUID organizationId);

    int countByUserIdAndDeletedFalse(UUID userId);

    boolean existsByUserIdAndVendorCodeAndDeletedFalse(UUID userId, String vendorCode);
}
