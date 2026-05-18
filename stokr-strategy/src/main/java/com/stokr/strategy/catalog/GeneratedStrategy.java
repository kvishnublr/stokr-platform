package com.stokr.strategy.catalog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for auto-generated strategy classes.
 * Carries asset-class metadata so developers know what instrument type
 * the template was generated for without reading the DB.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GeneratedStrategy {

    /** Canonical strategy key matching strategy_definitions.strategy_key */
    String strategyKey();

    /** EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY */
    String assetClass() default "EQUITY";

    /** NSE | NFO | MCX | CDS | BSE */
    String segment() default "NSE";

    /** Primary exchange for this strategy */
    String exchange() default "NSE";

    /** Default evaluation timeframe, e.g. 1m, 5m, 15m */
    String timeframe() default "1m";
}
