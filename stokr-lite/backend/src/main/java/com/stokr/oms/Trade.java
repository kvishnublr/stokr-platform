package com.stokr.oms;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "fill_price", nullable = false)
    private BigDecimal fillPrice;

    @Column(name = "fill_quantity", nullable = false)
    private Integer fillQuantity;

    @Column(name = "fill_time", nullable = false)
    @Builder.Default
    private Instant fillTime = Instant.now();
}
