package com.stokr.user.onboarding;

import com.stokr.common.events.auth.AuthAuditEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class TraderOnboardingRefreshListener {

    private final TraderOnboardingService traderOnboardingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerified(AuthAuditEvents.EmailVerified event) {
        refresh(event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTelegramVerified(AuthAuditEvents.TelegramVerified event) {
        refresh(event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWhatsappVerified(AuthAuditEvents.WhatsappVerified event) {
        refresh(event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBrokerZerodha(AuthAuditEvents.BrokerZerodhaConnected event) {
        refresh(event.userId());
    }

    private void refresh(java.util.UUID userId) {
        try {
            traderOnboardingService.refreshAfterContactChange(userId);
        } catch (Exception ex) {
            log.warn("onboarding.refresh.failed userId={}", userId, ex);
        }
    }
}
