package com.stokr.user.dto;

import jakarta.validation.constraints.Size;

public record UserProfileUpdateRequest(
        @Size(max = 200, message = "Display name cannot exceed 200 chars")
        String displayName,
        @Size(max = 64, message = "Timezone cannot exceed 64 chars")
        String timezone,
        @Size(max = 5000, message = "Preferences payload too large")
        String preferencesJson
) {
}
