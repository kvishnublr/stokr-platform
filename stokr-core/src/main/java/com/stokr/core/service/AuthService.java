package com.stokr.core.service;

import com.stokr.core.domain.User;
import com.stokr.core.dto.AuthDto.*;
import com.stokr.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${stokr.jwt.access-token-ttl-seconds:3600}")
    private long accessTokenTtl;

    @Value("${stokr.jwt.refresh-token-ttl-seconds:86400}")
    private long refreshTokenTtl;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .role("TRADER")
                .status("ACTIVE")
                .emailVerified(false)
                .build();

        User saved = userRepository.save(user);
        log.info("User registered: {}", saved.getEmail());

        String accessToken = jwtService.generateToken(saved.getId(), saved.getEmail(), saved.getRole());
        String refreshToken = jwtService.generateRefreshToken(saved.getId());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenTtl)
                .user(toUserDto(saved))
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedFalse(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!user.isActive()) {
            throw new IllegalArgumentException("Account is not active");
        }

        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        log.info("User logged in: {}", user.getEmail());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenTtl)
                .user(toUserDto(user))
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        UUID userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!user.isActive()) {
            throw new IllegalArgumentException("Account is not active");
        }

        String newAccessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        String newRefreshToken = jwtService.generateRefreshToken(user.getId());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenTtl)
                .user(toUserDto(user))
                .build();
    }

    @Transactional(readOnly = true)
    public UserDto getCurrentUser(UUID userId) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return toUserDto(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        log.info("User logged out");
    }

    private UserDto toUserDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .emailVerified(user.getEmailVerified())
                .organizationId(user.getOrganizationId())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
