package com.stokr.intraday.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdvIntelligenceTerminalService {

    private final UnifiedSignalTruthService unifiedSignalTruthService;

    public Map<String, Object> buildTerminal(UUID userId) {
        return unifiedSignalTruthService.buildTerminal(userId);
    }
}
