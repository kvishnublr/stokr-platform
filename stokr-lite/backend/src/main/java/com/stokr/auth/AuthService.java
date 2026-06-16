package com.stokr.auth;

import com.stokr.user.UserProfile;
import com.stokr.user.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthRepository authRepository;
    private final UserProfileRepository userProfileRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (authRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered");
        }

        AuthUser user = AuthUser.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(AuthRole.TRADER)
                .enabled(true)
                .build();
        authRepository.save(user);

        // Create default profile
        UserProfile profile = UserProfile.builder()
                .userId(user.getId())
                .name(request.name())
                .build();
        userProfileRepository.save(profile);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        AuthUser user = authRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Account is disabled");
        }

        return buildAuthResponse(user);
    }

    public AuthResponse refresh(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String email = jwtService.extractEmail(refreshToken);
        AuthUser user = authRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(AuthUser user) {
        return new AuthResponse(
                jwtService.generateToken(user),
                jwtService.generateRefreshToken(user),
                user.getId(),
                user.getEmail(),
                user.getRole().name()
        );
    }

    // DTOs
    public record RegisterRequest(String email, String password, String name) {}
    public record LoginRequest(String email, String password) {}
    public record AuthResponse(String accessToken, String refreshToken, Long userId, String email, String role) {}
}
