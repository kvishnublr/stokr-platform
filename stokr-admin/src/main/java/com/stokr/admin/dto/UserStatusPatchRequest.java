package com.stokr.admin.dto;

import jakarta.validation.constraints.NotNull;

public record UserStatusPatchRequest(@NotNull Boolean enabled) {
}
