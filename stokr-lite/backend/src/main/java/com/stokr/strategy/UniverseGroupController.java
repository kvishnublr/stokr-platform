package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/universe-groups")
@RequiredArgsConstructor
public class UniverseGroupController {

    private final UniverseGroupService groupService;
    private final StaticUniverseSyncService syncService;

    @GetMapping
    public ResponseEntity<List<UniverseGroup>> listAll() {
        return ResponseEntity.ok(groupService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UniverseGroup> getById(@PathVariable Long id) {
        return groupService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/symbols")
    public ResponseEntity<List<UniverseSymbol>> getSymbols(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.getSymbols(id));
    }

    @GetMapping("/{id}/symbols/resolved")
    public ResponseEntity<List<String>> getResolvedSymbols(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.resolveSymbolsForGroup(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UniverseGroup> create(@RequestBody Map<String, Object> req) {
        UniverseGroup g = groupService.create(
                (String) req.get("groupKey"),
                (String) req.get("displayName"),
                (String) req.getOrDefault("universeType", "CUSTOM"),
                (String) req.getOrDefault("exchange", "NSE"),
                (String) req.getOrDefault("assetClass", "EQUITY"),
                (String) req.getOrDefault("segment", "NSE"),
                (String) req.getOrDefault("instrumentType", "EQ"),
                (Boolean) req.getOrDefault("autoManaged", false)
        );
        return ResponseEntity.ok(g);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UniverseGroup> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        String displayName = req.containsKey("displayName") ? (String) req.get("displayName") : null;
        Boolean enabled = req.containsKey("enabled") ? (Boolean) req.get("enabled") : null;
        return ResponseEntity.ok(groupService.update(id, displayName, enabled));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        groupService.delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{id}/symbols")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> addSymbol(@PathVariable Long id, @RequestBody Map<String, String> req) {
        groupService.addSymbol(id, req.get("symbol"), req.get("tradingSymbol"), req.get("exchange"));
        return ResponseEntity.ok(Map.of("status", "added"));
    }

    @PostMapping("/{id}/symbols/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> bulkImport(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> symbols = (List<String>) req.get("symbols");
        boolean replace = (Boolean) req.getOrDefault("replaceExisting", false);
        groupService.bulkImportSymbols(id, symbols, replace);
        return ResponseEntity.ok(Map.of("status", "imported", "count", String.valueOf(symbols.size())));
    }

    @DeleteMapping("/{groupId}/symbols/{symbolId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> removeSymbol(@PathVariable Long symbolId) {
        groupService.removeSymbol(symbolId);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> sync(@PathVariable Long id) {
        UniverseGroup group = groupService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        if (group.isAutoManaged()) {
            syncService.syncGroup(group.getGroupKey());
            return ResponseEntity.ok(Map.of("status", "synced"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Group is not auto-managed"));
    }

    @GetMapping("/available-keys")
    public ResponseEntity<List<String>> getAvailableStaticKeys() {
        return ResponseEntity.ok(List.copyOf(syncService.getAvailableKeys()));
    }
}
