package com.stokr.user.repository;

import com.stokr.user.domain.BrokerOauthState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BrokerOauthStateRepository extends JpaRepository<BrokerOauthState, UUID> {

    Optional<BrokerOauthState> findByStateTokenAndConsumedFalseAndExpiresAtAfter(String stateToken, Instant now);
}
