package com.stokr.admin;

import com.stokr.auth.AuthRepository;
import com.stokr.auth.AuthRole;
import com.stokr.auth.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AuthRepository authRepository;

    public List<AuthUser> getAllUsers() {
        return authRepository.findAll();
    }

    public AuthUser getUser(Long userId) {
        return authRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    public long getUserCount() {
        return authRepository.count();
    }

    public AuthUser updateRole(Long userId, String role) {
        AuthUser user = getUser(userId);
        user.setRole(AuthRole.valueOf(role.toUpperCase()));
        return authRepository.save(user);
    }

    public AuthUser updateStatus(Long userId, boolean enabled) {
        AuthUser user = getUser(userId);
        user.setEnabled(enabled);
        return authRepository.save(user);
    }
}
