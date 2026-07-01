package com.stokr.engine;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pair_trades")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_key", nullable = false)
    private String pairKey;

    @Column(name = "symbol_a", nullable = false)
    private String symbolA;

    @Column(name = "symbol_b", nullable = false)
    private String symbolB;

    @Column(nullable = false)
    private String direction;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "daily_mean")
    private Double dailyMean;

    @Column(name = "daily_std")
    private Double dailyStd;

    @Column(name = "entry_z", nullable = false)
    private Double entryZ;

    @Column(name = "exit_z")
    private Double exitZ;

    @Column(name = "entry_price_a")
    private Double entryPriceA;

    @Column(name = "entry_price_b")
    private Double entryPriceB;

    @Column(name = "exit_price_a")
    private Double exitPriceA;

    @Column(name = "exit_price_b")
    private Double exitPriceB;

    @Column(name = "qty_a")
    private Integer qtyA;

    @Column(name = "qty_b")
    private Integer qtyB;

    @Column(name = "leg_a_pnl")
    private Double legAPnl;

    @Column(name = "leg_b_pnl")
    private Double legBPnl;

    @Column(name = "net_pnl")
    private Double netPnl;

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String mode;

    @Column(name = "order_id_a")
    private String orderIdA;

    @Column(name = "order_id_b")
    private String orderIdB;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "OPEN";
        if (mode == null) mode = "PAPER";
    }
}
