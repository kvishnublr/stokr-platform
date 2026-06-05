package com.stokr.bootstrap.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "market_data_staleness_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDataStalenessLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "check_time")
    private LocalDateTime checkTime;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "feed_source")
    private String feedSource;

    @Column(name = "last_price_age_seconds")
    private Integer lastPriceAgeSeconds;

    @Column(name = "last_volume_age_seconds")
    private Integer lastVolumeAgeSeconds;

    @Column(name = "last_open_interest_age_seconds")
    private Integer lastOpenInterestAgeSeconds;

    @Column(name = "is_stale")
    private Boolean isStale;

    @Column(name = "stale_threshold_seconds")
    private Integer staleThresholdSeconds;

    @Column(name = "alert_sent")
    private Boolean alertSent;

    @Column(name = "feed_restored_at")
    private LocalDateTime feedRestoredAt;
}
