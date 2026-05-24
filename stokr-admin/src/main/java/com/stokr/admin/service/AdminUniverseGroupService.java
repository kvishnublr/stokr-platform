package com.stokr.admin.service;

import com.stokr.admin.dto.BulkSymbolImportRequest;
import com.stokr.admin.dto.UniverseGroupCreateRequest;
import com.stokr.admin.dto.UniverseGroupDto;
import com.stokr.admin.dto.UniverseSymbolDto;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.catalog.UniverseSyncService;
import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUniverseGroupService {

    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;
    private final List<UniverseSyncService> syncServices;

    @Transactional(readOnly = true)
    public Page<UniverseGroupDto> page(Pageable pageable) {
        return groupRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<UniverseGroupDto> pageByAssetClass(String assetClass, Pageable pageable) {
        return groupRepository.findAllByAssetClass(assetClass, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public UniverseGroupDto getById(UUID id) {
        return groupRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new NotFoundException("Universe group not found: " + id));
    }

    @Transactional
    public UniverseGroupDto create(UniverseGroupCreateRequest req) {
        String key = req.groupKey().trim().toUpperCase();
        if (groupRepository.existsByGroupKey(key)) {
            throw new BadRequestException("Universe group key already exists: " + key);
        }
        StrategyUniverseGroup g = new StrategyUniverseGroup();
        g.setGroupKey(key);
        g.setDisplayName(req.displayName());
        g.setDescription(req.description());
        g.setUniverseType(nvl(req.universeType(), "CUSTOM"));
        g.setExchange(nvl(req.exchange(), "NSE"));
        g.setAssetClass(nvl(req.assetClass(), "EQUITY"));
        g.setSegment(nvl(req.segment(), "NSE"));
        g.setInstrumentType(nvl(req.instrumentType(), "EQ"));
        g.setAutoManaged(Boolean.TRUE.equals(req.autoManaged()));
        g.setEnabled(true);
        StrategyUniverseGroup saved = groupRepository.save(g);
        log.info("universe.group.created key={} assetClass={} segment={}", key, g.getAssetClass(), g.getSegment());
        return toDto(saved);
    }

    @Transactional
    public UniverseGroupDto toggleEnabled(UUID id, boolean enabled) {
        StrategyUniverseGroup g = groupRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Universe group not found: " + id));
        g.setEnabled(enabled);
        return toDto(groupRepository.save(g));
    }

    @Transactional(readOnly = true)
    public List<UniverseSymbolDto> listSymbols(UUID groupId) {
        return symbolRepository.findAllByGroupId(groupId).stream().map(this::toSymbolDto).toList();
    }

    /**
     * Bulk import symbols into a group.
     * If replaceExisting=true, all current symbols are removed first.
     */
    @Transactional
    public int bulkImport(UUID groupId, BulkSymbolImportRequest req) {
        StrategyUniverseGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Universe group not found: " + groupId));

        if (Boolean.TRUE.equals(req.replaceExisting())) {
            symbolRepository.deleteAllByGroupId(groupId);
        }

        int count = 0;
        for (BulkSymbolImportRequest.SymbolItem item : req.symbols()) {
            if (item.symbol() == null || item.symbol().isBlank()) continue;
            StrategyUniverseSymbol s = new StrategyUniverseSymbol();
            s.setGroup(group);
            s.setSymbol(item.symbol().trim().toUpperCase());
            s.setTradingSymbol(item.tradingSymbol());
            s.setUnderlyingSymbol(item.underlyingSymbol());
            s.setExchange(nvl(item.exchange(), group.getExchange()));
            s.setInstrumentType(nvl(item.instrumentType(), group.getInstrumentType()));
            s.setInstrumentToken(item.instrumentToken());
            s.setLotSize(item.lotSize());
            s.setTickSize(item.tickSize());
            s.setExpiry(item.expiry());
            s.setStrike(item.strike());
            s.setOptionType(item.optionType());
            s.setEnabled(true);
            symbolRepository.save(s);
            count++;
        }
        log.info("universe.symbols.imported groupId={} count={}", groupId, count);
        return count;
    }

    /**
     * Triggers auto-sync for auto-managed groups using registered UniverseSyncService implementations.
     */
    @Transactional
    public int syncGroup(UUID groupId) {
        StrategyUniverseGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Universe group not found: " + groupId));
        if (!group.isAutoManaged()) {
            throw new BadRequestException("Group is not auto-managed: " + group.getGroupKey());
        }
        int total = 0;
        for (UniverseSyncService svc : syncServices) {
            if (svc.supportedGroupKeys().contains(group.getGroupKey())) {
                total += svc.sync(group.getGroupKey());
            }
        }
        log.info("universe.sync.triggered groupKey={} synced={}", group.getGroupKey(), total);
        return total;
    }

    /**
     * Syncs ALL auto-managed universe groups with latest symbols.
     * This will populate NIFTY50, NIFTY100, BANKNIFTY, FINNIFTY, etc. with their complete symbol lists.
     */
    @Transactional
    public Map<String, Object> syncAllAutoManagedGroups() {
        Map<String, Integer> results = new HashMap<>();
        int totalSynced = 0;

        for (UniverseSyncService svc : syncServices) {
            for (String groupKey : svc.supportedGroupKeys()) {
                try {
                    // Try to sync via the service
                    int count = svc.sync(groupKey);
                    results.put(groupKey, count);
                    totalSynced += count;
                    log.info("universe.auto.sync.done groupKey={} symbols={}", groupKey, count);
                } catch (Exception ex) {
                    log.warn("universe.auto.sync.failed groupKey={} reason={}", groupKey, ex.getMessage());
                    results.put(groupKey + "_ERROR", -1);
                }
            }
        }

        log.info("universe.auto.sync.all_complete totalSynced={} groups={}", totalSynced, results.size());
        return Map.of(
            "totalSymbolsSynced", totalSynced,
            "groupResults", results,
            "timestamp", System.currentTimeMillis()
        );
    }

    private UniverseGroupDto toDto(StrategyUniverseGroup g) {
        long count = symbolRepository.countByGroupIdAndEnabledTrue(g.getId());
        return new UniverseGroupDto(
                g.getId(), g.getGroupKey(), g.getDisplayName(), g.getDescription(),
                g.getUniverseType(), g.getExchange(), g.getAssetClass(), g.getSegment(),
                g.getInstrumentType(), g.isAutoManaged(), g.isEnabled(),
                count, g.getCreatedAt(), g.getUpdatedAt()
        );
    }

    private UniverseSymbolDto toSymbolDto(StrategyUniverseSymbol s) {
        return new UniverseSymbolDto(
                s.getId(), s.getGroup().getId(), s.getSymbol(), s.getTradingSymbol(),
                s.getUnderlyingSymbol(), s.getExchange(), s.getInstrumentType(),
                s.getInstrumentToken(), s.getLotSize(), s.getTickSize(),
                s.getExpiry(), s.getStrike(), s.getOptionType(), s.isEnabled(), s.getCreatedAt()
        );
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }
}
