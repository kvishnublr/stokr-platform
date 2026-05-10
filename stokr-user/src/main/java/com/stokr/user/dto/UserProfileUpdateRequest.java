package com.stokr.user.dto;

import com.stokr.user.domain.AccountStatus;
import com.stokr.user.domain.RiskProfile;
import com.stokr.user.domain.SubscriptionPlan;

public record UserProfileUpdateRequest(
        String displayName,
        String timezone,
        String preferencesJson,
        SubscriptionPlan subscriptionPlan,
        RiskProfile riskProfile,
        AccountStatus accountStatus,
        String brokerAccountPlaceholder,
        String tradingPreferencesJson
) {
}
