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

@Service
@RequiredArgsConstructor
public class TraderContactService {

    private final AuthUserRepository authUserRepository;

    @Transactional
    public ContactDto update(UUID userId, ContactPatchRequest req) {
        if (req.telegramUsername() == null && req.whatsAppE164() == null) {
            throw new BadRequestException("Provide telegramUsername and/or whatsAppE164");
        }
        AuthUser u = authUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (req.telegramUsername() != null) {
            String t = req.telegramUsername().trim().replaceFirst("^@", "");
            if (t.isEmpty()) {
                throw new BadRequestException("Telegram username cannot be empty");
            }
            u.setTelegramUsername(t.toLowerCase(Locale.ROOT));
            u.setTelegramVerified(false);
            u.setTelegramChatId(null);
        }
        if (req.whatsAppE164() != null) {
            String w = req.whatsAppE164().trim();
            if (!w.isEmpty()) {
                u.setWhatsappE164(w);
                u.setWhatsappVerified(false);
            }
        }
        authUserRepository.save(u);
        return new ContactDto(u.getTelegramUsername(), u.getWhatsappE164(), u.isTelegramVerified(), u.isWhatsappVerified());
    }

    public record ContactPatchRequest(String telegramUsername, String whatsAppE164) {
    }

    public record ContactDto(String telegramUsername, String whatsAppE164, boolean telegramVerified, boolean whatsAppVerified) {
    }
}
