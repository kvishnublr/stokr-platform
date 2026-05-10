package com.stokr.user.repository;

import com.stokr.user.domain.AuthTelegramVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthTelegramVerificationTokenRepository extends JpaRepository<AuthTelegramVerificationToken, UUID> {

    Optional<AuthTelegramVerificationToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(String tokenHash, Instant now);
}
