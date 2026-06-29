package com.stokr.delivery;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "nse_delivery_data",
       uniqueConstraints = @UniqueConstraint(columnNames = {"trade_date", "symbol"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class NseDeliveryData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Column(name = "series", length = 10)
    private String series;

    @Column(name = "open_price", precision = 12, scale = 2)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 12, scale = 2)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 12, scale = 2)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 12, scale = 2)
    private BigDecimal closePrice;

    @Column(name = "prev_close", precision = 12, scale = 2)
    private BigDecimal prevClose;

    @Column(name = "total_qty")
    private Long totalQty;

    @Column(name = "deliv_qty")
    private Long delivQty;

    @Column(name = "deliv_pct", precision = 6, scale = 2)
    private BigDecimal delivPct;

    @Column(name = "high_52w", precision = 12, scale = 2)
    private BigDecimal high52w;

    @Column(name = "low_52w", precision = 12, scale = 2)
    private BigDecimal low52w;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
