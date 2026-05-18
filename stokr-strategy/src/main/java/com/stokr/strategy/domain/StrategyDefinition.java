package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

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

    /**
     * JSON document: schemaVersion, executionCapabilities, parameters[] (metadata-driven launch / backtest).
     */
    @Column(name = "parameter_metadata_json", columnDefinition = "text")
    private String parameterMetadataJson;

    // ── Catalog management columns (added V31) ────────────────────────────

    /** INTRADAY | SWING | POSITIONAL | SCALPING */
    @Column(name = "strategy_type", length = 64)
    private String strategyType = "INTRADAY";

    /** ALL | LIVE_ONLY | PAPER_ONLY | BACKTEST_ONLY */
    @Column(name = "execution_mode", length = 64)
    private String executionMode = "ALL";

    /** Short class name of the generated signal generator, e.g. MyNewStrategySignalGenerator */
    @Column(name = "template_class_name", length = 256)
    private String templateClassName;

    /** Fully-qualified path written to disk by StrategyTemplateGeneratorService */
    @Column(name = "generated_class_path", length = 512)
    private String generatedClassPath;

    /** Default candle timeframe used at runtime, e.g. 1m, 5m, 15m */
    @Column(name = "default_timeframe", length = 16)
    private String defaultTimeframe = "1m";

    @Column(name = "default_exchange", length = 16)
    private String defaultExchange = "NSE";

    @Column(name = "supports_backtest", nullable = false)
    private boolean supportsBacktest = true;

    @Column(name = "supports_live", nullable = false)
    private boolean supportsLive = false;

    @Column(name = "supports_paper", nullable = false)
    private boolean supportsPaper = true;

    @Column(name = "catalog_version", length = 32)
    private String catalogVersion = "1.0";

    /** UUID of the admin user who created this entry via the admin panel */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "created_by")
    private UUID createdBy;

    /** True once StrategyTemplateGeneratorService has written the .java file to disk */
    @Column(name = "template_generated", nullable = false)
    private boolean templateGenerated = false;
}
