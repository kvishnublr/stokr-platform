package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class UniverseGroupService {

    private final UniverseGroupRepository groupRepository;
    private final UniverseSymbolRepository symbolRepository;

    public List<UniverseGroup> listAll() {
        return groupRepository.findAll();
    }

    public List<UniverseGroup> listEnabled() {
        return groupRepository.findByEnabledTrue();
    }

    public Optional<UniverseGroup> findById(Long id) {
        return groupRepository.findById(id);
    }

    public Optional<UniverseGroup> findByKey(String groupKey) {
        return groupRepository.findByGroupKey(groupKey);
    }

    @Transactional
    public UniverseGroup create(String groupKey, String displayName, String universeType,
                                 String exchange, String assetClass, String segment,
                                 String instrumentType, boolean autoManaged) {
        if (groupRepository.existsByGroupKey(groupKey)) {
            throw new IllegalArgumentException("Group key already exists: " + groupKey);
        }
        UniverseGroup group = UniverseGroup.builder()
                .groupKey(groupKey.toUpperCase())
                .displayName(displayName)
                .universeType(universeType)
                .exchange(exchange)
                .assetClass(assetClass)
                .segment(segment)
                .instrumentType(instrumentType)
                .autoManaged(autoManaged)
                .enabled(true)
                .build();
        return groupRepository.save(group);
    }

    @Transactional
    public UniverseGroup update(Long id, String displayName, Boolean enabled) {
        UniverseGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + id));
        if (displayName != null) group.setDisplayName(displayName);
        if (enabled != null) group.setEnabled(enabled);
        return groupRepository.save(group);
    }

    @Transactional
    public void delete(Long id) {
        symbolRepository.deleteByGroupId(id);
        groupRepository.deleteById(id);
    }

    public List<UniverseSymbol> getSymbols(Long groupId) {
        return symbolRepository.findByGroupIdAndEnabledTrue(groupId);
    }

    @Transactional
    public void addSymbol(Long groupId, String symbol, String tradingSymbol, String exchange) {
        UniverseSymbol s = UniverseSymbol.builder()
                .groupId(groupId)
                .symbol(symbol.toUpperCase())
                .tradingSymbol(tradingSymbol != null ? tradingSymbol.toUpperCase() : symbol.toUpperCase())
                .exchange(exchange != null ? exchange.toUpperCase() : "NSE")
                .enabled(true)
                .build();
        symbolRepository.save(s);
    }

    @Transactional
    public void bulkImportSymbols(Long groupId, List<String> symbols, boolean replaceExisting) {
        if (replaceExisting) {
            symbolRepository.deleteByGroupId(groupId);
        }
        for (String raw : symbols) {
            String sym = raw.trim().toUpperCase();
            if (sym.isEmpty()) continue;
            UniverseSymbol s = UniverseSymbol.builder()
                    .groupId(groupId)
                    .symbol(sym)
                    .tradingSymbol(sym)
                    .exchange("NSE")
                    .enabled(true)
                    .build();
            symbolRepository.save(s);
        }
    }

    @Transactional
    public void removeSymbol(Long symbolId) {
        symbolRepository.deleteById(symbolId);
    }

    public List<String> resolveSymbolsForGroup(Long groupId) {
        return getSymbols(groupId).stream().map(UniverseSymbol::getSymbol).toList();
    }

    public Map<String, List<String>> resolveSymbolsForGroups(Collection<Long> groupIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Long gid : groupIds) {
            groupRepository.findById(gid).ifPresent(g -> {
                result.put(g.getGroupKey(), resolveSymbolsForGroup(gid));
            });
        }
        return result;
    }
}
