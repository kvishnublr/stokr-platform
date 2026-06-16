package com.stokr.oms;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "positions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "avg_price", nullable = false)
    @Builder.Default
    private BigDecimal avgPrice = BigDecimal.ZERO;

    @Column(name = "realized_pnl", nullable = false)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "unrealized_pnl", nullable = false)
    @Builder.Default
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isOpen() {
        return quantity != 0;
    }
}
