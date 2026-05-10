package com.stokr.user.service;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.exception.NotFoundException;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.dto.TraderOnboardingSummaryDto;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TraderSelfStatusService {

    private final AuthUserRepository authUserRepository;
    private final BrokerAccountRepository brokerAccountRepository;

    @Transactional(readOnly = true)
    public TraderOnboardingSummaryDto onboardingSummary(UUID userId) {
        AuthUser u = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        boolean zc = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                .map(this::brokerConnected)
                .orElse(false);
        return new TraderOnboardingSummaryDto(
                u.getId(),
                u.isEmailVerified(),
                u.isTelegramVerified(),
                u.isWhatsappVerified(),
                u.isOnboardingComplete(),
                u.isLiveTradingApproved(),
                zc,
                u.getTelegramUsername(),
                u.getWhatsappE164()
        );
    }

    private boolean brokerConnected(BrokerAccount b) {
        String s = b.getStatus();
        if (s == null) {
            return false;
        }
        return switch (s.trim().toUpperCase()) {
            case "CONNECTED", "ACTIVE", "LINKED", "OK" -> true;
            default -> false;
        };
    }
}
