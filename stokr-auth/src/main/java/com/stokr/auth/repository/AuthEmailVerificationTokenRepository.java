package com.stokr.auth.repository;

import com.stokr.auth.domain.AuthEmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthEmailVerificationTokenRepository extends JpaRepository<AuthEmailVerificationToken, UUID> {

    Optional<AuthEmailVerificationToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(String tokenHash, Instant now);

    Optional<AuthEmailVerificationToken> findFirstByUser_IdAndDeletedFalseOrderByCreatedAtDesc(UUID userId);
}
