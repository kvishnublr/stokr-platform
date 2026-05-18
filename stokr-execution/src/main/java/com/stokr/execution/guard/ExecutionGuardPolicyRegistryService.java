package com.stokr.execution.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ExecutionGuardPolicyRegistryService {

    private final ExecutionGuardPolicyRegistryRepository registryRepository;
    private final ExecutionGuardPolicyOverrideRepository overrideRepository;
    private final ExecutionGuardPolicyAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    private final AtomicReference<CacheSnapshot> cache = new AtomicReference<>(new CacheSnapshot(List.of(), Instant.EPOCH));

    @PostConstruct
    void init() {
        reload();
    }

    @Transactional(readOnly = true)
    public void reload() {
        List<ExecutionGuardPolicyOverride> active = overrideRepository.findByDeletedFalseAndEnabledTrueOrderByUpdatedAtDesc();
        cache.set(new CacheSnapshot(active, Instant.now()));
    }

    @Transactional
    public void upsertOverride(
            UUID actorUserId,
            ExecutionGuardScopeType scopeType,
            String scopeKey,
            ExecutionGuardMode guardMode,
            Map<String, Object> patch
    ) {
        ExecutionGuardPolicyRegistry registry = registryRepository.findByRegistryKeyIgnoreCaseAndDeletedFalse("DEFAULT")
                .orElseGet(() -> {
                    ExecutionGuardPolicyRegistry r = new ExecutionGuardPolicyRegistry();
                    r.setRegistryKey("DEFAULT");
                    r.setDisplayName("Default execution guard policy");
                    r.setActive(true);
                    r.setRevision(1L);
                    return registryRepository.save(r);
                });

        ExecutionGuardPolicyOverride target = overrideRepository.findByDeletedFalseAndEnabledTrueOrderByUpdatedAtDesc().stream()
                .filter(x -> x.getScopeType() == scopeType)
                .filter(x -> x.getGuardMode() == guardMode)
                .filter(x -> norm(x.getScopeKey()).equals(norm(scopeKey)))
                .findFirst()
                .orElseGet(() -> {
                    ExecutionGuardPolicyOverride o = new ExecutionGuardPolicyOverride();
                    o.setRegistry(registry);
                    o.setScopeType(scopeType);
                    o.setScopeKey(scopeKey);
                    o.setGuardMode(guardMode);
                    o.setEnabled(true);
                    o.setRevision(1L);
                    return o;
                });

        String before = serialize(target);
        applyPatch(target, patch);
        target.setRevision((target.getRevision() == null ? 0L : target.getRevision()) + 1L);
        overrideRepository.save(target);
        audit("UPSERT", actorUserId, scopeType, scopeKey, guardMode, before, serialize(target), "Policy override upsert");
        reload();
    }

    @Transactional
    public void disableOverride(UUID actorUserId, UUID overrideId) {
        Optional<ExecutionGuardPolicyOverride> maybe = overrideRepository.findById(overrideId);
        if (maybe.isEmpty()) return;
        ExecutionGuardPolicyOverride row = maybe.get();
        String before = serialize(row);
        row.setEnabled(false);
        row.setDeleted(true);
        overrideRepository.save(row);
        audit("DISABLE", actorUserId, row.getScopeType(), row.getScopeKey(), row.getGuardMode(), before, null, "Policy override disabled");
        reload();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOverrides(int limit) {
        List<ExecutionGuardPolicyOverride> rows = new ArrayList<>(cache.get().rows());
        rows.sort(Comparator.comparing(ExecutionGuardPolicyOverride::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.stream().limit(Math.max(1, Math.min(500, limit))).map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.getId().toString());
            m.put("scopeType", r.getScopeType().name());
            m.put("scopeKey", r.getScopeKey());
            m.put("guardMode", r.getGuardMode() != null ? r.getGuardMode().name() : null);
            m.put("enabled", r.isEnabled());
            m.put("revision", r.getRevision());
            m.put("maxSignalAgeMs", r.getMaxSignalAgeMs());
            m.put("maxDriftPct", r.getMaxDriftPct());
            m.put("maxSpreadPct", r.getMaxSpreadPct());
            m.put("maxSlippagePct", r.getMaxSlippagePct());
            m.put("softStaleFeedMs", r.getSoftStaleFeedMs());
            m.put("hardStaleFeedMs", r.getHardStaleFeedMs());
            m.put("volatilityThresholdPct", r.getVolatilityThresholdPct());
            m.put("minLiquidityQty", r.getMinLiquidityQty());
            m.put("allowExitDuringStaleFeed", r.getAllowExitDuringStaleFeed());
            m.put("allowSoftFailExecution", r.getAllowSoftFailExecution());
            m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
            return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAudit(int limit) {
        return auditRepository.findByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(500, limit))))
                .stream().map(a -> {
                    Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("id", a.getId().toString());
                    out.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
                    out.put("actorUserId", a.getActorUserId() != null ? a.getActorUserId().toString() : null);
                    out.put("action", a.getAction());
                    out.put("scopeType", a.getScopeType() != null ? a.getScopeType().name() : null);
                    out.put("scopeKey", a.getScopeKey());
                    out.put("guardMode", a.getGuardMode() != null ? a.getGuardMode().name() : null);
                    out.put("before", a.getBeforePayload());
                    out.put("after", a.getAfterPayload());
                    out.put("notes", a.getNotes());
                    out.put("revision", a.getRevision());
                    return out;
                }).toList();
    }

    public ExecutionGuardPolicy applyOverrides(
            ExecutionGuardPolicy base,
            String strategyKey,
            String symbol,
            ExecutionGuardMode mode,
            String sessionKey,
            String regimeKey
    ) {
        CacheSnapshot snapshot = cache.get();
        if (snapshot.rows().isEmpty()) return base;
        ExecutionGuardPolicy out = base;
        out = overlay(out, match(snapshot.rows(), ExecutionGuardScopeType.STRATEGY, strategyKey, mode));
        out = overlay(out, match(snapshot.rows(), ExecutionGuardScopeType.SYMBOL, symbol, mode));
        out = overlay(out, match(snapshot.rows(), ExecutionGuardScopeType.SESSION, sessionKey, mode));
        out = overlay(out, match(snapshot.rows(), ExecutionGuardScopeType.REGIME, regimeKey, mode));
        return out;
    }

    private static ExecutionGuardPolicyOverride match(List<ExecutionGuardPolicyOverride> rows, ExecutionGuardScopeType scope, String key, ExecutionGuardMode mode) {
        String normalized = norm(key);
        return rows.stream()
                .filter(r -> r.getScopeType() == scope)
                .filter(r -> r.getGuardMode() == null || r.getGuardMode() == mode)
                .filter(r -> norm(r.getScopeKey()).equals(normalized))
                .max(Comparator.comparing(ExecutionGuardPolicyOverride::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static ExecutionGuardPolicy overlay(ExecutionGuardPolicy base, ExecutionGuardPolicyOverride o) {
        if (o == null) return base;
        return new ExecutionGuardPolicy(
                o.getMaxSignalAgeMs() != null ? o.getMaxSignalAgeMs() : base.maxSignalAgeMs(),
                o.getMaxDriftPct() != null ? o.getMaxDriftPct() : base.maxDriftPct(),
                o.getMaxSpreadPct() != null ? o.getMaxSpreadPct() : base.maxSpreadPct(),
                o.getMaxSlippagePct() != null ? o.getMaxSlippagePct() : base.maxSlippagePct(),
                o.getSoftStaleFeedMs() != null ? o.getSoftStaleFeedMs() : base.softStaleFeedMs(),
                o.getHardStaleFeedMs() != null ? o.getHardStaleFeedMs() : base.hardStaleFeedMs(),
                o.getVolatilityThresholdPct() != null ? o.getVolatilityThresholdPct() : base.volatilityThresholdPct(),
                o.getMinLiquidityQty() != null ? o.getMinLiquidityQty() : base.minLiquidityQty(),
                o.getAllowExitDuringStaleFeed() != null ? o.getAllowExitDuringStaleFeed() : base.allowExitDuringStaleFeed(),
                o.getAllowSoftFailExecution() != null ? o.getAllowSoftFailExecution() : base.allowSoftFailExecution()
        );
    }

    private void applyPatch(ExecutionGuardPolicyOverride t, Map<String, Object> patch) {
        if (patch.containsKey("maxSignalAgeMs")) t.setMaxSignalAgeMs(longVal(patch.get("maxSignalAgeMs")));
        if (patch.containsKey("maxDriftPct")) t.setMaxDriftPct(decVal(patch.get("maxDriftPct")));
        if (patch.containsKey("maxSpreadPct")) t.setMaxSpreadPct(decVal(patch.get("maxSpreadPct")));
        if (patch.containsKey("maxSlippagePct")) t.setMaxSlippagePct(decVal(patch.get("maxSlippagePct")));
        if (patch.containsKey("softStaleFeedMs")) t.setSoftStaleFeedMs(longVal(patch.get("softStaleFeedMs")));
        if (patch.containsKey("hardStaleFeedMs")) t.setHardStaleFeedMs(longVal(patch.get("hardStaleFeedMs")));
        if (patch.containsKey("volatilityThresholdPct")) t.setVolatilityThresholdPct(decVal(patch.get("volatilityThresholdPct")));
        if (patch.containsKey("minLiquidityQty")) t.setMinLiquidityQty(decVal(patch.get("minLiquidityQty")));
        if (patch.containsKey("allowExitDuringStaleFeed")) t.setAllowExitDuringStaleFeed(boolVal(patch.get("allowExitDuringStaleFeed")));
        if (patch.containsKey("allowSoftFailExecution")) t.setAllowSoftFailExecution(boolVal(patch.get("allowSoftFailExecution")));
    }

    private void audit(String action, UUID actor, ExecutionGuardScopeType scopeType, String scopeKey, ExecutionGuardMode mode, String before, String after, String notes) {
        ExecutionGuardPolicyAudit a = new ExecutionGuardPolicyAudit();
        a.setActorUserId(actor);
        a.setAction(action);
        a.setScopeType(scopeType);
        a.setScopeKey(scopeKey);
        a.setGuardMode(mode);
        a.setBeforePayload(before);
        a.setAfterPayload(after);
        a.setNotes(notes);
        auditRepository.save(a);
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static Long longVal(Object x) {
        if (x == null) return null;
        if (x instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(x).trim()); } catch (Exception ignored) { return null; }
    }

    private static BigDecimal decVal(Object x) {
        if (x == null) return null;
        if (x instanceof BigDecimal b) return b;
        try { return new BigDecimal(String.valueOf(x).trim()); } catch (Exception ignored) { return null; }
    }

    private static Boolean boolVal(Object x) {
        if (x == null) return null;
        if (x instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(x).trim());
    }

    private record CacheSnapshot(List<ExecutionGuardPolicyOverride> rows, Instant loadedAt) {}
}
