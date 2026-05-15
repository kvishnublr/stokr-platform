package com.stokr.user.repository;

import com.stokr.user.domain.PlatformBrokerFeedSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformBrokerFeedSessionRepository extends JpaRepository<PlatformBrokerFeedSession, UUID> {

    Optional<PlatformBrokerFeedSession> findByVendorCodeIgnoreCaseAndDeletedFalse(String vendorCode);
}
