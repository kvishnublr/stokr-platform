#!/usr/bin/env python3
"""Fix resolveUniverseGroup to use findById instead of getGroupKey"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"
with open(f) as fp:
    code = fp.read()

old = """    private String resolveUniverseGroup(Long strategyId) {
        List<com.stokr.strategy.StrategyUniverseMapping> mappings = universeMappingRepo.findByStrategyId(strategyId);
        if (mappings != null && !mappings.isEmpty()) {
            return universeGroupService.getGroupKey(mappings.get(0).getUniverseGroupId());
        }
        return "NIFTY_100";
    }"""

new = """    private String resolveUniverseGroup(Long strategyId) {
        List<com.stokr.strategy.StrategyUniverseMapping> mappings = universeMappingRepo.findByStrategyId(strategyId);
        if (mappings != null && !mappings.isEmpty()) {
            Long groupId = mappings.get(0).getUniverseGroupId();
            return universeGroupService.findById(groupId)
                .map(com.stokr.strategy.UniverseGroup::getGroupKey)
                .orElse("NIFTY_100");
        }
        return "NIFTY_100";
    }"""

code = code.replace(old, new)

with open(f, 'w') as fp:
    fp.write(code)
print("resolveUniverseGroup fixed")
