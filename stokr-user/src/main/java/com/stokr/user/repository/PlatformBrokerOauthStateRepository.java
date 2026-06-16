package com.stokr.user.repository;

import com.stokr.user.domain.PlatformBrokerOauthState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlatformBrokerOauthStateRepository extends JpaRepository<PlatformBrokerOauthState, UUID> {

    Optional<PlatformBrokerOauthState> findByStateTokenAndConsumedFalseAndExpiresAtAfter(String stateToken, Instant now);

    Optional<PlatformBrokerOauthState> findByStateToken(String stateToken);
}
