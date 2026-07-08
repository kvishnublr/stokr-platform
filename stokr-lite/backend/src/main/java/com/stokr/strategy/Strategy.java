package com.stokr.strategy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "strategies")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Strategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "strategy_type", nullable = false)
    private String strategyType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_schema", columnDefinition = "jsonb")
    private String paramsSchema;

    @Column(name = "asset_class", nullable = false)
    @Builder.Default
    private String assetClass = "EQUITY";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "timeframe")
    @Builder.Default
    private String timeframe = "INTRADAY";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
