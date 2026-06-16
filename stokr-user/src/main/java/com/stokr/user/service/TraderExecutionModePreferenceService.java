package com.stokr.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.domain.UserProfile;
import com.stokr.user.dto.TraderExecutionModePreferenceDto;
import com.stokr.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TraderExecutionModePreferenceService {

    private static final String KEY = "workspaceExecutionMode";
    private static final String PAPER = "PAPER";
    private static final String LIVE = "LIVE";

    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public TraderExecutionModePreferenceDto get(UUID userId) {
        UserProfile p = userProfileRepository.findByUserIdAndDeletedFalse(userId).orElse(null);
        if (p == null || p.getTradingPreferencesJson() == null || p.getTradingPreferencesJson().isBlank()) {
            return new TraderExecutionModePreferenceDto(PAPER);
        }
        try {
            JsonNode n = objectMapper.readTree(p.getTradingPreferencesJson());
            String mode = normalize(n.path(KEY).asText(PAPER));
            return new TraderExecutionModePreferenceDto(mode);
        } catch (Exception ignore) {
            return new TraderExecutionModePreferenceDto(PAPER);
        }
    }

    @Transactional
    public TraderExecutionModePreferenceDto update(UUID userId, String requestedMode, boolean liveApproved) {
        String mode = normalize(requestedMode);
        if (LIVE.equals(mode) && !liveApproved) {
            throw new BadRequestException("LIVE mode is not approved for this trader profile.");
        }
        UserProfile p = userProfileRepository.findByUserIdAndDeletedFalse(userId).orElseGet(() -> {
            UserProfile np = new UserProfile();
            np.setUserId(userId);
            return userProfileRepository.save(np);
        });

        ObjectNode root;
        try {
            String raw = p.getTradingPreferencesJson();
            root = raw == null || raw.isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception e) {
            root = objectMapper.createObjectNode();
        }
        root.put(KEY, mode);
        p.setTradingPreferencesJson(root.toString());
        userProfileRepository.save(p);
        return new TraderExecutionModePreferenceDto(mode);
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PAPER;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!PAPER.equals(v) && !LIVE.equals(v)) {
            throw new BadRequestException("executionMode must be PAPER or LIVE");
        }
        return v;
    }
}

