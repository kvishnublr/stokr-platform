package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "strategies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Strategy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "creator_id")
    private UUID creatorId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String parameters = "{}";

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Builder.Default
    private Boolean isActive = true;

    @Builder.Default
    private Boolean isPublic = false;

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
}
