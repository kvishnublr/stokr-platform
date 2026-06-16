package com.stokr.user.contact;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TraderContactService {

    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern TELEGRAM_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{5,32}$");
    private final AuthUserRepository authUserRepository;

    @Transactional(readOnly = true)
    public ContactDto get(UUID userId) {
        AuthUser u = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        return new ContactDto(u.getMobilePhone(), u.getTelegramUsername(), u.getWhatsappE164(), u.isTelegramVerified(), u.isWhatsappVerified());
    }

    @Transactional
    public ContactDto update(UUID userId, ContactPatchRequest req) {
        if (req.mobilePhone() == null && req.telegramUsername() == null && req.whatsAppE164() == null) {
            throw new BadRequestException("Provide mobilePhone, telegramUsername and/or whatsAppE164");
        }
        AuthUser u = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (req.mobilePhone() != null) {
            String phone = req.mobilePhone().trim();
            if (phone.isEmpty()) {
                u.setMobilePhone(null);
            } else if (!E164_PATTERN.matcher(phone).matches()) {
                throw new BadRequestException("mobilePhone must be in E.164 format, e.g. +919876543210");
            } else {
                u.setMobilePhone(phone);
            }
        }
        if (req.telegramUsername() != null) {
            String t = req.telegramUsername().trim().replaceFirst("^@", "");
            if (t.isEmpty()) {
                u.setTelegramUsername(null);
                u.setTelegramVerified(false);
                u.setTelegramChatId(null);
            } else if (!TELEGRAM_PATTERN.matcher(t).matches()) {
                throw new BadRequestException("Telegram username must be 5-32 chars (letters, digits, underscore)");
            } else {
                u.setTelegramUsername(t.toLowerCase(Locale.ROOT));
                u.setTelegramVerified(false);
                u.setTelegramChatId(null);
            }
        }
        if (req.whatsAppE164() != null) {
            String w = req.whatsAppE164().trim();
            if (w.isEmpty()) {
                u.setWhatsappE164(null);
                u.setWhatsappVerified(false);
            } else if (!E164_PATTERN.matcher(w).matches()) {
                throw new BadRequestException("whatsAppE164 must be in E.164 format, e.g. +919876543210");
            } else {
                u.setWhatsappE164(w);
                u.setWhatsappVerified(false);
            }
        }
        authUserRepository.save(u);
        return new ContactDto(u.getMobilePhone(), u.getTelegramUsername(), u.getWhatsappE164(), u.isTelegramVerified(), u.isWhatsappVerified());
    }

    public record ContactPatchRequest(String mobilePhone, String telegramUsername, String whatsAppE164) {
    }

    public record ContactDto(
            String mobilePhone,
            String telegramUsername,
            String whatsAppE164,
            boolean telegramVerified,
            boolean whatsAppVerified
    ) {
    }
}
