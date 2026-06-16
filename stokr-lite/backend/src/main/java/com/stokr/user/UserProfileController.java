package com.stokr.user;

import com.stokr.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService profileService;

    @GetMapping
    public ResponseEntity<UserProfile> getProfile() {
        return ResponseEntity.ok(profileService.getProfile(SecurityUtils.currentUserId()));
    }

    @PutMapping
    public ResponseEntity<UserProfile> updateProfile(@RequestBody UpdateDto dto) {
        var request = new UserProfileService.UpdateProfileRequest(
                dto.name(), dto.phone(), dto.totalCapital(), dto.riskProfile());
        return ResponseEntity.ok(profileService.updateProfile(SecurityUtils.currentUserId(), request));
    }

    public record UpdateDto(String name, String phone, BigDecimal totalCapital, String riskProfile) {}
}
