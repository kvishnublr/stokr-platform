package com.stokr.user.onboarding;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Recomputes derived onboarding flags when verification events occur (email/Telegram/broker sync hooks).
 */
@Service
@RequiredArgsConstructor
public class TraderOnboardingService {

    private final AuthUserRepository authUserRepository;
    private final BrokerAccountRepository brokerAccountRepository;

    @Transactional
    public void refreshAfterContactChange(UUID userId) {
        AuthUser user = authUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        boolean brokerOk = brokerAccountRepository.findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId)
                .map(this::brokerLinked)
                .orElse(false);
        boolean complete = user.isEmailVerified() && user.isTelegramVerified() && brokerOk;
        if (user.isOnboardingComplete() != complete) {
            user.setOnboardingComplete(complete);
            authUserRepository.save(user);
        }
    }

    private boolean brokerLinked(BrokerAccount account) {
        String status = account.getStatus();
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "CONNECTED", "ACTIVE", "LINKED", "OK" -> true;
            default -> false;
        };
    }
}
