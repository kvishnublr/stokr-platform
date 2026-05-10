package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "strategy_definitions")
public class StrategyDefinition extends BaseEntity {

    @Column(name = "strategy_key", nullable = false, unique = true, length = 128)
    private String strategyKey;

    /**
     * Human-readable title shown in UI (falls back to derived label when null).
     */
    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "visible_to_users", nullable = false)
    private boolean visibleToUsers = true;

    @Column(name = "risk_level", nullable = false, length = 32)
    private String riskLevel = "MEDIUM";

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "tags_json", columnDefinition = "text")
    private String tagsJson;

    @Column(name = "icon_key", length = 64)
    private String iconKey;

    @Column(name = "min_capital", precision = 24, scale = 8)
    private BigDecimal minCapital;

    @Column(name = "popularity_score", precision = 12, scale = 4)
    private BigDecimal popularityScore;

    @Column(name = "win_rate", precision = 12, scale = 6)
    private BigDecimal winRate;

    @Column(name = "avg_monthly_return", precision = 12, scale = 6)
    private BigDecimal avgMonthlyReturn;

    @Column(name = "announcement_banner", length = 500)
    private String announcementBanner;
}
