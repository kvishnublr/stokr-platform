package com.stokr.core.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

public class UserDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserDtoResponse {
        private UUID id;
        private UUID organizationId;
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private String status;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateUserRequest {
        private String email;
        private String password;
        private String firstName;
        private String lastName;
        private String role;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateUserRequest {
        private String firstName;
        private String lastName;
        private String role;
        private String status;
    }
}