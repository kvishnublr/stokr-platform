package com.stokr.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository repository;

    public UserProfile getProfile(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found for user: " + userId));
    }

    @Transactional
    public UserProfile updateProfile(Long userId, UpdateProfileRequest request) {
        UserProfile profile = getProfile(userId);
        if (request.name() != null) profile.setName(request.name());
        if (request.phone() != null) profile.setPhone(request.phone());
        if (request.totalCapital() != null) profile.setTotalCapital(request.totalCapital());
        if (request.riskProfile() != null) profile.setRiskProfile(request.riskProfile());
        return repository.save(profile);
    }

    public BigDecimal getAvailableCapital(Long userId) {
        return getProfile(userId).getTotalCapital();
    }

    public record UpdateProfileRequest(String name, String phone, BigDecimal totalCapital, String riskProfile) {}
}
