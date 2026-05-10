package com.stokr.user.repository;

import com.stokr.user.domain.AuthWhatsappVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthWhatsappVerificationTokenRepository extends JpaRepository<AuthWhatsappVerificationToken, UUID> {

    Optional<AuthWhatsappVerificationToken> findTopByUser_IdAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID userId,
            Instant now
    );
}
