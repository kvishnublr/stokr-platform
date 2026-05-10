package com.stokr.auth.repository;

import com.stokr.auth.domain.AuthPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthPasswordResetTokenRepository extends JpaRepository<AuthPasswordResetToken, UUID> {

    Optional<AuthPasswordResetToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(String tokenHash, Instant now);
}
