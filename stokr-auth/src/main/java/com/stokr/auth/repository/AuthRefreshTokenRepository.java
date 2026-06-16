package com.stokr.auth.repository;

import com.stokr.auth.domain.AuthRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, UUID> {

    Optional<AuthRefreshToken> findByTokenHashAndDeletedFalse(String tokenHash);
}
