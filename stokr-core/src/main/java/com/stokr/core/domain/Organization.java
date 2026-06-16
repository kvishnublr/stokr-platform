package com.stokr.core.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(name = "plan_type", nullable = false)
    @Builder.Default
    private String planType = "FREE";

    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 3;

    @Column(name = "max_strategies")
    @Builder.Default
    private Integer maxStrategies = 10;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = false;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean canAddUser(int currentCount) {
        return currentCount < maxUsers;
    }

    public boolean canAddStrategy(int currentCount) {
        return currentCount < maxStrategies;
    }

    public boolean isPremium() {
        return !"FREE".equalsIgnoreCase(planType);
    }
}
