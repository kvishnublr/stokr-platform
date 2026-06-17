package com.stokr.strategy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "universe_groups")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UniverseGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_key", nullable = false, unique = true)
    private String groupKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "universe_type", nullable = false)
    @Builder.Default
    private String universeType = "INDEX_CONSTITUENTS"; // INDEX_CONSTITUENTS | CUSTOM | SECTOR

    @Column(nullable = false)
    @Builder.Default
    private String exchange = "NSE";

    @Column(name = "asset_class", nullable = false)
    @Builder.Default
    private String assetClass = "EQUITY";

    @Column(nullable = false)
    @Builder.Default
    private String segment = "NSE";

    @Column(name = "instrument_type")
    @Builder.Default
    private String instrumentType = "EQ";

    @Column(name = "auto_managed")
    @Builder.Default
    private boolean autoManaged = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
