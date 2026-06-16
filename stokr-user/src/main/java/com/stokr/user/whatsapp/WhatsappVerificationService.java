package com.stokr.user.whatsapp;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.common.notification.whatsapp.WhatsAppProvider;
import com.stokr.user.domain.AuthWhatsappVerificationToken;
import com.stokr.user.repository.AuthWhatsappVerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WhatsappVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthWhatsappVerificationTokenRepository tokenRepository;
    private final AuthUserRepository authUserRepository;
    private final WhatsAppProvider whatsAppProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void sendOtp(UUID userId) {
        AuthUser user = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getWhatsappE164() == null || user.getWhatsappE164().isBlank()) {
            throw new BadRequestException("Save WhatsApp number on your profile first");
        }
        tokenRepository.findTopByUser_IdAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .ifPresent(prev -> {
                    if (prev.getLastSentAt() != null
                            && prev.getLastSentAt().isAfter(Instant.now().minus(30, ChronoUnit.SECONDS))) {
                        throw new BadRequestException("Wait a few seconds before requesting another code");
                    }
                    prev.setConsumed(true);
                    tokenRepository.save(prev);
                });

        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        AuthWhatsappVerificationToken row = new AuthWhatsappVerificationToken();
        row.setUser(user);
        row.setOtpHash(sha256Hex(otp));
        row.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        row.setConsumed(false);
        row.setResendCount(1);
        row.setLastSentAt(Instant.now());
        tokenRepository.save(row);

        whatsAppProvider.sendVerificationOtp(user.getWhatsappE164(), otp, row.getExpiresAt());
    }

    @Transactional
    public void verify(UUID userId, String otpRaw) {
        AuthUser user = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        AuthWhatsappVerificationToken row = tokenRepository
                .findTopByUser_IdAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .orElseThrow(() -> new BadRequestException("No active OTP — request a new code"));
        if (!row.getOtpHash().equals(sha256Hex(otpRaw.trim()))) {
            throw new BadRequestException("Invalid OTP");
        }
        row.setConsumed(true);
        tokenRepository.save(row);
        user.setWhatsappVerified(true);
        authUserRepository.save(user);
        eventPublisher.publishEvent(new AuthAuditEvents.WhatsappVerified(userId, user.getWhatsappE164(), Instant.now()));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
