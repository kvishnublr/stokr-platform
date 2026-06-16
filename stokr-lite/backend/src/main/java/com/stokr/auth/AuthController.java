package com.stokr.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthService.AuthResponse> register(
            @Valid @RequestBody RegisterDto dto) {
        var request = new AuthService.RegisterRequest(dto.email(), dto.password(), dto.name());
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthResponse> login(
            @Valid @RequestBody LoginDto dto) {
        var request = new AuthService.LoginRequest(dto.email(), dto.password());
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthService.AuthResponse> refresh(
            @RequestBody RefreshDto dto) {
        return ResponseEntity.ok(authService.refresh(dto.refreshToken()));
    }

    // Request DTOs with validation
    public record RegisterDto(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6) String password,
            @NotBlank String name
    ) {}

    public record LoginDto(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record RefreshDto(@NotBlank String refreshToken) {}
}
