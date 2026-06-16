package com.stokr.admin.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperationalHistoryService {

    private final OperationalEventStore operationalEventStore;

    @Transactional
    public void record(String topic, Map<String, Object> payload, String actor, UUID targetUserId) {
        operationalEventStore.append(topic, payload, actor, targetUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshotSection() {
        List<Map<String, Object>> rows = operationalEventStore.recentRows();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recent", rows);
        m.put("note", "Append-only admin / platform audit tail (last 50 rows).");
        return m;
    }
}
