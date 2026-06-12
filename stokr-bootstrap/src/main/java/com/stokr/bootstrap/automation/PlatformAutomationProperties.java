package com.stokr.bootstrap.automation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stokr.platform.automation")
public class PlatformAutomationProperties {

    private boolean enabled = true;

    private String preMarketCron = "0 40 5 * * MON-FRI";

    private String preOpenCron = "0 55 8 * * MON-FRI";

    private String inSessionCron = "0 */30 9-16 * * MON-FRI";

    /** Renew Zerodha tokens when expiry is within this window (hours). */
    private int tokenRefreshBeforeHours = 4;
}
