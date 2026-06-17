package com.stokr.chartink;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for per-trader configuration.
 * Allows traders to view and update their auto-trading settings.
 */
@RestController
@RequestMapping("/api/chartink/trader-config")
@RequiredArgsConstructor
public class TraderConfigController {

    private final TraderConfigService traderConfigService;

    @GetMapping("/{userId}")
    public ResponseEntity<TraderConfig> getConfig(@PathVariable Long userId) {
        return ResponseEntity.ok(traderConfigService.getConfig(userId));
    }

    @PutMapping("/{userId}")
    public ResponseEntity<TraderConfig> updateConfig(@PathVariable Long userId,
                                                      @RequestBody TraderConfig patch) {
        return ResponseEntity.ok(traderConfigService.updateConfig(userId, patch));
    }

    @PostMapping("/{userId}/mode")
    public ResponseEntity<String> setMode(@PathVariable Long userId,
                                           @RequestParam String mode) {
        try {
            TraderConfig.Mode m = TraderConfig.Mode.valueOf(mode.toUpperCase());
            traderConfigService.toggleMode(userId, m);
            return ResponseEntity.ok("Mode set to " + m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid mode. Use PAPER or LIVE");
        }
    }
}
