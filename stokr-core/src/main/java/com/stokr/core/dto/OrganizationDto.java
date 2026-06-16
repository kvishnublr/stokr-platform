package com.stokr.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

public class OrganizationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateOrganizationRequest {
        @NotBlank(message = "Name is required")
        @Size(max = 255)
        private String name;

        @NotBlank(message = "Slug is required")
        @Size(max = 100)
        private String slug;

        private String planType; // FREE, PRO
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateOrganizationRequest {
        @Size(max = 255)
        private String name;

        private String planType;

        @Size(max = 255)
        private String billingEmail;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrganizationResponse {
        private UUID id;
        private String name;
        private String slug;
        private String planType;
        private Integer maxUsers;
        private Integer maxStrategies;
        private String billingEmail;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrganizationUsageResponse {
        private UUID organizationId;
        private String planType;
        private int userCount;
        private int strategyCount;
        private int brokerCount;
        private int maxUsers;
        private int maxStrategies;
        private int maxBrokers;
        private boolean canAddUser;
        private boolean canAddStrategy;
        private boolean canAddBroker;
    }
}
