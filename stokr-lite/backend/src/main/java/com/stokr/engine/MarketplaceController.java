package com.stokr.engine;

import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyRepository;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * User-facing marketplace and paper trading endpoints.
 * <p>
 * These are what a subscribed user interacts with:
 * - Browse strategies with performance stats
 * - Start paper trading with virtual ₹20K
 * - Track P&L and admin commission
 * - Switch between paper and live mode
 */
@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
public class MarketplaceController {

    private final PaperTradingService paperService;
    private final StrategyService strategyService;
    private final StrategyRepository strategyRepo;
    private final DeploymentService deploymentService;
    private final DeploymentRepository deploymentRepo;

    // ──── Strategy Marketplace ────

    /**
     * GET /api/marketplace/strategies
     * Browse all enabled strategies with live performance metrics.
     * No auth required — this is the public marketplace.
     */
    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> getStrategies() {
        List<Map<String, Object>> strategies = paperService.getMarketplaceStrategies();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalStrategies", strategies.size());
        response.put("strategies", strategies);
        response.put("note", "Start with paper trading — no deposit needed. Virtual ₹20,000 balance.");
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/marketplace/strategies/{id}
     * Detailed view of a single strategy with full performance history.
     */
    @GetMapping("/strategies/{id}")
    public ResponseEntity<Map<String, Object>> getStrategyDetail(@PathVariable Long id) {
        Strategy s = strategyService.getStrategy(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", s.getId());
        detail.put("name", s.getName());
        detail.put("type", s.getStrategyType());
        detail.put("description", s.getDescription());
        detail.put("paramsSchema", s.getParamsSchema());
        detail.put("performance", paperService.getStrategyPerformance(id));
        detail.put("activeUsers", deploymentRepo.countByStrategyIdAndActiveAndLive(id));
        return ResponseEntity.ok(detail);
    }

    // ──── Paper Trading ────

    /**
     * POST /api/marketplace/paper/deploy
     * One-click paper deployment. Starts paper trading with virtual balance.
     *
     * Body: { "strategyId": 1, "capital": 35000 }
     */
    @PostMapping("/paper/deploy")
    public ResponseEntity<Map<String, Object>> paperDeploy(
            Authentication auth, @RequestBody Map<String, Object> body) {
        Long userId = getUserId(auth);
        Long strategyId = Long.valueOf(body.get("strategyId").toString());
        BigDecimal capital = new BigDecimal(body.getOrDefault("capital", "20000").toString());

        // Check: within virtual wallet balance
        Map<String, Object> wallet = paperService.getWalletStats(userId);
        BigDecimal balance = new BigDecimal(wallet.get("currentBalance").toString());
        if (capital.compareTo(balance) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Insufficient virtual balance. Available: ₹" + balance + ", Requested: ₹" + capital));
        }

        Deployment d = paperService.createPaperDeployment(userId, strategyId, capital);

        return ResponseEntity.ok(Map.of(
            "status", "deployed",
            "deploymentId", d.getId(),
            "mode", "PAPER",
            "strategy", getStrategyName(d),
            "capital", capital,
            "message", "Paper trading started. Monitor your P&L at /api/marketplace/wallet"
        ));
    }

    /**
     * GET /api/marketplace/wallet
     * User's virtual wallet — current balance, P&L, win rate.
     */
    @GetMapping("/wallet")
    public ResponseEntity<Map<String, Object>> getWallet(Authentication auth) {
        Long userId = getUserId(auth);
        Map<String, Object> stats = paperService.getWalletStats(userId);

        // Add user's active deployments
        List<Deployment> deployments = deploymentRepo.findByUserIdAndStatus(userId, "ACTIVE");
        List<Map<String, Object>> activeDeployments = new ArrayList<>();
        for (Deployment d : deployments) {
            Map<String, Object> dd = new LinkedHashMap<>();
            dd.put("id", d.getId());
            dd.put("strategy", getStrategyName(d));
            dd.put("capital", d.getCapital());
            dd.put("isLive", d.isLive());
            dd.put("createdAt", d.getCreatedAt());
            activeDeployments.add(dd);
        }
        stats.put("activeDeployments", activeDeployments);
        stats.put("totalDeployed", deployments.stream()
            .map(d -> d.getCapital() != null ? d.getCapital() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add));

        return ResponseEntity.ok(stats);
    }

    /**
     * POST /api/marketplace/deployment/{id}/toggle-live
     * Switch a paper deployment to live mode (requires real broker connection).
     */
    @PostMapping("/deployment/{id}/toggle-live")
    public ResponseEntity<Map<String, Object>> toggleLive(
            Authentication auth, @PathVariable Long id) {
        Long userId = getUserId(auth);
        Deployment d = deploymentRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Deployment not found"));

        if (!d.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Not your deployment"));
        }

        d.setMode(d.isLive() ? "PAPER" : "LIVE");
        deploymentRepo.save(d);

        return ResponseEntity.ok(Map.of(
            "deploymentId", d.getId(),
            "isLive", d.isLive(),
            "message", d.isLive() ? "Switched to LIVE mode. Real money is now at risk."
                                   : "Switched to PAPER mode."
        ));
    }

    // ──── Performance & Commission ────

    /**
     * GET /api/marketplace/performance
     * User's trading performance dashboard.
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformance(Authentication auth) {
        Long userId = getUserId(auth);
        Map<String, Object> perf = new LinkedHashMap<>();

        perf.put("wallet", paperService.getWalletStats(userId));

        // Strategy-wise breakdown
        List<Deployment> deployments = deploymentRepo.findByUserIdAndStatus(userId, "ACTIVE");
        List<Map<String, Object>> strategyBreakdown = new ArrayList<>();
        for (Deployment d : deployments) {
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("deploymentId", d.getId());
            sp.put("strategyName", getStrategyName(d));
            sp.put("capital", d.getCapital());
            sp.put("mode", d.isLive() ? "LIVE" : "PAPER");
            Map<String, Object> sPerf = paperService.getStrategyPerformance(d.getStrategyId());
            sp.put("performance", sPerf);
            strategyBreakdown.add(sp);
        }
        perf.put("strategyBreakdown", strategyBreakdown);

        // Monthly P&L history (from signals)
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        Map<String, Object> commission = paperService.calculateProfitShare(userId,
            thirtyDaysAgo, LocalDate.now());
        perf.put("commission", commission);

        return ResponseEntity.ok(perf);
    }

    /**
     * GET /api/marketplace/leaderboard
     * Top performing users (gamification — drives engagement).
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<List<Map<String, Object>>> getLeaderboard() {
        // Simplified: top 10 users by paper P&L
        List<VirtualWallet> wallets = deploymentRepo.findAllVirtualWalletsByPnl(10);
        List<Map<String, Object>> board = new ArrayList<>();
        for (int i = 0; i < wallets.size(); i++) {
            VirtualWallet w = wallets.get(i);
            board.add(Map.of(
                "rank", i + 1,
                "userId", w.getUserId(),
                "pnl", w.getTotalPnl(),
                "winRate", w.getTotalTrades() > 0
                    ? Math.round((double) w.getWinningTrades() / w.getTotalTrades() * 100) + "%"
                    : "N/A",
                "trades", w.getTotalTrades()
            ));
        }
        return ResponseEntity.ok(board);
    }

    private String getStrategyName(Deployment d) {
        try {
            return strategyService.getStrategy(d.getStrategyId()).getName();
        } catch (Exception e) {
            return "Strategy #" + d.getStrategyId();
        }
    }

    private Long getUserId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return 1L; // anon -> test user
        if (auth.getPrincipal() instanceof com.stokr.auth.AuthUser u) return u.getId();
        return 1L;
    }
}
