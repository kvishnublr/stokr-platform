package com.stokr.engine;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Virtual paper trading wallet per user.
 * Users get ₹20,000 virtual balance to test strategies risk-free.
 * P&L is tracked here separately from real broker accounts.
 */
@Entity
@Table(name = "virtual_wallets")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VirtualWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "initial_balance", nullable = false)
    @Builder.Default
    private BigDecimal initialBalance = new BigDecimal("20000");

    @Column(name = "current_balance", nullable = false)
    @Builder.Default
    private BigDecimal currentBalance = new BigDecimal("20000");

    @Column(name = "total_pnl", nullable = false)
    @Builder.Default
    private BigDecimal totalPnl = BigDecimal.ZERO;

    @Column(name = "total_trades", nullable = false)
    @Builder.Default
    private int totalTrades = 0;

    @Column(name = "winning_trades", nullable = false)
    @Builder.Default
    private int winningTrades = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
