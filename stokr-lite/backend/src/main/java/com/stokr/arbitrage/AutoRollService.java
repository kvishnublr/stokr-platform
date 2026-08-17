package com.stokr.arbitrage;

import com.stokr.broker.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Auto-roll for Butterfly positions: if a position sits outside its profit zone
 * continuously for a configurable window, close it automatically and propose a
 * re-centered replacement -- which still requires a one-click confirm before it's
 * actually entered (see confirmRoll/dismissRoll). A per-lineage roll cap stops a
 * trending market from turning this into an endless re-bet loop: once the cap is hit,
 * this service stops touching that lineage entirely and leaves it to the existing
 * stop-loss/target/expiry handling in OptionArbAutoExecService.checkRollover.
 *
 * Off by default (autoRollEnabled=false) -- this is new, higher-risk automation that
 * can execute LIVE trades once turned on, same as every other Auto-Trade toggle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRollService {

    private final LivePositionRepository positionRepo;
    private final AutoRollStateRepository autoRollStateRepo;
    private final OptionArbOpportunityRepository oppRepo;
    private final OptionArbAutoExecService autoExecService;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    @Scheduled(fixedDelayString = "60000", initialDelay = 45000)
    public synchronized void monitorAndRoll() {
        Map<String, Object> settings = autoExecService.getSettings();

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        List<LivePosition> flies = positionRepo.findAllOpen().stream()
            .filter(p -> "BUTTERFLY_SPREAD".equals(p.getStrategyType()))
            .filter(p -> p.getLegs() != null && p.getLegs().size() == 3)
            .toList();
        if (flies.isEmpty()) return;

        for (LivePosition pos : flies) {
            try {
                // Per-underlying settings, same pattern as the Auto-Execute Engine cards --
                // e.g. "nifty" + "AutoRollEnabled" -> settings.get("niftyAutoRollEnabled").
                String uKey = pos.getUnderlying() != null ? pos.getUnderlying().toLowerCase() : "";
                if (!Boolean.TRUE.equals(settings.get(uKey + "AutoRollEnabled"))) continue;
                int breachMinutes = ((Number) settings.getOrDefault(uKey + "AutoRollBreachMinutes", 5)).intValue();
                int maxRolls = ((Number) settings.getOrDefault(uKey + "AutoRollMaxRolls", 2)).intValue();
                monitorOne(pos, breachMinutes, maxRolls, settings);
            } catch (Exception e) {
                log.error("Auto-roll monitor failed for position {}: {}", pos.getId(), e.getMessage(), e);
            }
        }
    }

    private void monitorOne(LivePosition pos, int breachMinutes, int maxRolls, Map<String, Object> settings) {
        AutoRollState state = autoRollStateRepo.findActiveByCurrentPositionId(pos.getId()).orElse(null);
        if (state == null) {
            List<Map<String, Object>> initLegs = pos.getLegs();
            String optionType = (String) initLegs.get(0).get("optionType");
            state = AutoRollState.builder()
                .originalPositionId(pos.getId())
                .currentPositionId(pos.getId())
                .underlying(pos.getUnderlying())
                .optionType(optionType)
                .lots(pos.getLots())
                .rollCount(0)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        }

        if (state.getRollCount() >= maxRolls) {
            if (!"MAX_ROLLS_REACHED".equals(state.getStatus())) {
                state.setStatus("MAX_ROLLS_REACHED");
                state.setUpdatedAt(LocalDateTime.now());
                autoRollStateRepo.save(state);
                autoExecService.addLog("AUTO_ROLL", "CAP_REACHED", pos.getUnderlying() + " " + pos.getStrike()
                    + " — reached max " + maxRolls + " auto-rolls; left to ride on normal stop-loss/target/expiry");
            }
            return;
        }

        List<Map<String, Object>> legs = pos.getLegs();
        int k1 = legs.stream().mapToInt(l -> ((Number) l.get("strike")).intValue()).min().orElse(0);
        int k3 = legs.stream().mapToInt(l -> ((Number) l.get("strike")).intValue()).max().orElse(0);
        int width = k3 - k1;
        if (width <= 0) return;

        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : OptionChainService.getLotSize(pos.getUnderlying());
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        double entryCost = pos.getEntryCost() != null ? pos.getEntryCost().doubleValue() : 0;
        double costPerLot = (lotSize * lots) > 0 ? entryCost / (lotSize * lots) : 0;
        double breakevenLower = k1 + costPerLot;
        double breakevenUpper = k3 - costPerLot;

        double spot = fetchSpot(pos.getUnderlying());
        if (spot <= 0) return;

        boolean outsideZone = spot < breakevenLower || spot > breakevenUpper;

        if (!outsideZone) {
            if (state.getBreachStartedAt() != null) {
                state.setBreachStartedAt(null);
                state.setUpdatedAt(LocalDateTime.now());
                autoRollStateRepo.save(state);
            } else if (state.getId() == null) {
                autoRollStateRepo.save(state);
            }
            return;
        }

        if (state.getBreachStartedAt() == null) {
            state.setBreachStartedAt(LocalDateTime.now());
            state.setUpdatedAt(LocalDateTime.now());
            autoRollStateRepo.save(state);
            return;
        }

        long minutesBreached = Duration.between(state.getBreachStartedAt(), LocalDateTime.now()).toMinutes();
        if (minutesBreached < breachMinutes) {
            autoRollStateRepo.save(state);
            return;
        }

        triggerRoll(state, pos, legs, spot, settings, maxRolls);
    }

    private void triggerRoll(AutoRollState state, LivePosition pos, List<Map<String, Object>> legs,
                              double spot, Map<String, Object> settings, int maxRolls) {
        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);

        double pnl = 0;
        try {
            List<String> symbols = legs.stream().map(l -> (String) l.get("symbol")).filter(Objects::nonNull).toList();
            Map<String, OptionChainService.OptionQuote> quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
            pnl = autoExecService.computeMultiLegPnl(pos, quotes);
        } catch (Exception e) {
            log.debug("Auto-roll: pnl fetch failed for {}: {}", pos.getId(), e.getMessage());
        }

        boolean closed;
        if (isPaper) {
            closed = true;
        } else {
            closed = closeLive(pos, broker);
        }
        if (!closed) {
            autoExecService.addLog("AUTO_ROLL", "CLOSE_FAILED", pos.getUnderlying() + " " + pos.getStrike()
                + " — breach close failed, will retry next cycle");
            autoRollStateRepo.save(state);
            return;
        }

        pos.setStatus("CLOSED");
        pos.setExitedAt(LocalDateTime.now());
        pos.setCurrentPnl(BigDecimal.valueOf(pnl));
        positionRepo.save(pos);

        if (pos.getOpportunityId() != null) {
            double closedPnl = pnl;
            oppRepo.findById(pos.getOpportunityId()).ifPresent(opp -> {
                opp.setStatus("EXITED");
                opp.setExitTime(LocalDateTime.now());
                opp.setPnlAfterCosts(BigDecimal.valueOf(closedPnl));
                oppRepo.save(opp);
            });
        }

        autoExecService.addLog("AUTO_ROLL", "CLOSED_ON_BREACH", pos.getUnderlying() + " " + pos.getStrike()
            + " — spot stayed outside profit zone; closed automatically (P&L ₹" + Math.round(pnl) + ")");

        int newRollCount = state.getRollCount() + 1;
        state.setRollCount(newRollCount);
        state.setLastClosedPnl(BigDecimal.valueOf(pnl));
        state.setBreachStartedAt(null);
        state.setUpdatedAt(LocalDateTime.now());

        if (newRollCount > maxRolls) {
            state.setStatus("MAX_ROLLS_REACHED");
            state.setCurrentPositionId(null);
            autoRollStateRepo.save(state);
            autoExecService.addLog("AUTO_ROLL", "CAP_REACHED", pos.getUnderlying() + " " + pos.getStrike()
                + " — closed, but max " + maxRolls + " rolls already used; no replacement proposed");
            return;
        }

        int width = legs.stream().mapToInt(l -> ((Number) l.get("strike")).intValue()).max().orElse(0)
            - legs.stream().mapToInt(l -> ((Number) l.get("strike")).intValue()).min().orElse(0);
        Map<String, Object> candidate = buildRecenteredCandidate(pos.getUnderlying(), state.getOptionType(), width, spot);

        if (candidate == null) {
            state.setStatus("CLOSE_ONLY");
            state.setCurrentPositionId(null);
            autoRollStateRepo.save(state);
            autoExecService.addLog("AUTO_ROLL", "NO_REENTRY", pos.getUnderlying() + " " + pos.getStrike()
                + " — closed, but couldn't construct a valid re-centered butterfly (illiquid quotes or invalid cost)");
            return;
        }

        state.setPendingProposal(candidate);
        state.setStatus("PENDING_CONFIRM");
        state.setCurrentPositionId(null);
        autoRollStateRepo.save(state);
        autoExecService.addLog("AUTO_ROLL", "PENDING_CONFIRM", pos.getUnderlying() + " new butterfly "
            + candidate.get("strikes") + " proposed (roll " + newRollCount + "/" + maxRolls + ") — awaiting confirm");
    }

    private boolean closeLive(LivePosition pos, String broker) {
        try {
            Long userId = brokerAccountRepo.findByStatus("ACTIVE").stream()
                .findFirst().map(BrokerAccount::getUserId).orElse(null);
            if (userId == null) return false;
            List<BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker, "ACTIVE");
            if (accounts.isEmpty()) return false;
            BrokerAccount account = accounts.get(0);
            BrokerAdapter adapter = brokerService.getAdapter(broker);
            return autoExecService.squareOffMultiLegPosition(account, adapter, pos);
        } catch (Exception e) {
            log.error("Auto-roll live close failed for {}: {}", pos.getId(), e.getMessage());
            return false;
        }
    }

    /** Builds a fresh, ATM-centered butterfly of the same width as the one that just closed. */
    private Map<String, Object> buildRecenteredCandidate(String underlying, String optionType, int width, double spot) {
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int lotSize = OptionChainService.getLotSize(underlying);
            int k2 = (int) (Math.round(spot / step) * step);
            int k1 = k2 - width;
            int k3 = k2 + width;

            LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
            if (expiry == null) return null;

            String s1 = optionChainService.buildNfoSymbol(underlying, expiry, k1, optionType);
            String s2 = optionChainService.buildNfoSymbol(underlying, expiry, k2, optionType);
            String s3 = optionChainService.buildNfoSymbol(underlying, expiry, k3, optionType);
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(s1, s2, s3));
            OptionChainService.OptionQuote q1 = quotes.get(s1);
            OptionChainService.OptionQuote q2 = quotes.get(s2);
            OptionChainService.OptionQuote q3 = quotes.get(s3);
            if (q1 == null || q2 == null || q3 == null) return null;
            if (q1.ask <= 0 || q2.bid <= 0 || q3.ask <= 0) return null;

            double cost = q1.ask - 2 * q2.bid + q3.ask;
            if (cost <= 0 || cost >= width) return null;

            double turnover = (q1.ask + 2 * q2.bid + q3.ask) * lotSize;
            double sttBuy = (q1.ask + q3.ask) * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            double sttSell = (2 * q2.bid) * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
            double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
            double sebi = turnover * ArbitrageCosts.SEBI_RATE;
            double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
            double stamp = turnover * ArbitrageCosts.STAMP_RATE;
            double entryCosts = sttBuy + sttSell + brokerage + exchange + sebi + gst + stamp;

            double grossLoss = cost * lotSize;
            double grossProfit = (width - cost) * lotSize;
            double maxLoss = grossLoss + entryCosts;
            double maxProfit = Math.max(0, grossProfit - entryCosts);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("underlying", underlying);
            m.put("optionType", optionType);
            m.put("strikes", k1 + "/" + k2 + "/" + k3);
            m.put("k1", k1); m.put("k2", k2); m.put("k3", k3);
            m.put("strike", k2);
            m.put("action", "BUY_FLY " + optionType + " (" + k1 + "/" + k2 + "/" + k3 + ")");
            m.put("strategyType", "BUTTERFLY_SPREAD");
            m.put("width", width);
            m.put("costPerLot", Math.round(cost * 100.0) / 100.0);
            m.put("maxLoss", Math.round(maxLoss * 100.0) / 100.0);
            m.put("maxProfit", Math.round(maxProfit * 100.0) / 100.0);
            m.put("entryCosts", Math.round(entryCosts * 100.0) / 100.0);
            m.put("breakevenLower", Math.round((k1 + cost) * 100.0) / 100.0);
            m.put("breakevenUpper", Math.round((k3 - cost) * 100.0) / 100.0);
            m.put("lotSize", lotSize);
            m.put("spotAtProposal", spot);
            m.put("expiryDate", expiry.toString());
            m.put("legList", List.of(
                ButterflySpreadService.leg(k1, optionType, "BUY", 1, q1.ask),
                ButterflySpreadService.leg(k2, optionType, "SELL", 2, q2.bid),
                ButterflySpreadService.leg(k3, optionType, "BUY", 1, q3.ask)));
            return m;
        } catch (Exception e) {
            log.error("Auto-roll: failed to build recentered candidate for {}: {}", underlying, e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> listPending() {
        return autoRollStateRepo.findByStatus("PENDING_CONFIRM").stream().map(AutoRollState::toMap).toList();
    }

    public synchronized Map<String, Object> confirmRoll(Long stateId) {
        Map<String, Object> result = new LinkedHashMap<>();
        AutoRollState state = autoRollStateRepo.findById(stateId).orElse(null);
        if (state == null) { result.put("status", "ERROR"); result.put("message", "Auto-roll state not found"); return result; }
        if (!"PENDING_CONFIRM".equals(state.getStatus())) {
            result.put("status", "ERROR"); result.put("message", "Not awaiting confirmation (status=" + state.getStatus() + ")"); return result;
        }
        Map<String, Object> candidate = state.getPendingProposal();
        if (candidate == null) { result.put("status", "ERROR"); result.put("message", "Proposal data missing"); return result; }

        int lots = state.getLots() != null ? state.getLots() : 1;
        Map<String, Object> settings = autoExecService.getSettings();
        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legList = (List<Map<String, Object>>) candidate.get("legList");

        OptionArbOpportunity opp = OptionArbOpportunity.builder()
            .scanTime(LocalDateTime.now())
            .underlying((String) candidate.get("underlying"))
            .type("BUTTERFLY_SPREAD")
            .strike(((Number) candidate.get("k2")).intValue())
            .action((String) candidate.get("action"))
            .legs("Auto-roll re-centered butterfly " + candidate.get("strikes"))
            .strategyType("BUTTERFLY_SPREAD")
            .description("Auto-rolled from position after breakeven breach")
            .spotPrice(BigDecimal.valueOf(((Number) candidate.get("spotAtProposal")).doubleValue()))
            .edgeAfterCosts(BigDecimal.valueOf(((Number) candidate.get("maxProfit")).doubleValue()))
            .expiryDate(LocalDate.parse((String) candidate.get("expiryDate")))
            .status("RUNNING")
            .build();
        opp.setLegList(legList);
        opp = oppRepo.save(opp);

        Long newPositionId;
        if (!isPaper) {
            Map<String, Object> liveResult = autoExecService.manualExecuteLive(opp, lots, broker);
            if (!"SUCCESS".equals(liveResult.get("status"))) {
                result.put("status", "ERROR");
                result.put("message", "Live re-entry failed: " + liveResult.get("message"));
                autoExecService.addLog("AUTO_ROLL", "REENTRY_FAILED", opp.getUnderlying() + " " + liveResult.get("message"));
                return result;
            }
            newPositionId = positionRepo.findByOpportunityIdIn(List.of(opp.getId())).stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .findFirst().map(LivePosition::getId).orElse(null);
        } else {
            newPositionId = enterPaperPosition(opp, lots);
        }

        if (newPositionId == null) {
            result.put("status", "ERROR");
            result.put("message", "Re-entry order placed but couldn't confirm the resulting position — check Positions page");
            return result;
        }

        state.setCurrentPositionId(newPositionId);
        state.setStatus("ACTIVE");
        state.setPendingProposalJson(null);
        state.setUpdatedAt(LocalDateTime.now());
        autoRollStateRepo.save(state);

        autoExecService.addLog("AUTO_ROLL", "REENTERED", opp.getUnderlying() + " " + candidate.get("strikes")
            + " — roll confirmed, new position #" + newPositionId + " OPEN");

        result.put("status", "SUCCESS");
        result.put("positionId", newPositionId);
        return result;
    }

    private Long enterPaperPosition(OptionArbOpportunity opp, int lots) {
        try {
            List<Map<String, Object>> legs = opp.getLegList();
            int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
            List<String> symbols = new ArrayList<>();
            List<Map<String, Object>> resolvedLegs = new ArrayList<>();
            for (Map<String, Object> leg : legs) {
                int strike = ((Number) leg.get("strike")).intValue();
                String optionType = (String) leg.get("optionType");
                String sym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), strike, optionType);
                Map<String, Object> resolved = new LinkedHashMap<>(leg);
                resolved.put("symbol", sym);
                resolvedLegs.add(resolved);
                symbols.add(sym);
            }
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(symbols);
            double entryCost = 0;
            for (Map<String, Object> leg : resolvedLegs) {
                String sym = (String) leg.get("symbol");
                double live = (quotes.containsKey(sym) && quotes.get(sym).lastPrice > 0)
                    ? quotes.get(sym).lastPrice
                    : (leg.get("price") instanceof Number n ? n.doubleValue() : 0);
                leg.put("price", live);
                int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                boolean isBuy = "BUY".equals(leg.get("side"));
                entryCost += (isBuy ? live : -live) * qtyMult;
            }
            entryCost = Math.abs(entryCost) * lotSize * lots;

            LivePosition livePos = LivePosition.builder()
                .userId(1L)
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(opp.getAction())
                .strategyType(opp.getStrategyType())
                .lots(lots)
                .lotSize(lotSize)
                .targetEdge(opp.getEdgeAfterCosts())
                .entryCost(BigDecimal.valueOf(entryCost))
                .status("OPEN")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
            livePos.setLegs(resolvedLegs);
            livePos = positionRepo.save(livePos);
            return livePos.getId();
        } catch (Exception e) {
            log.error("Auto-roll paper re-entry failed: {}", e.getMessage());
            return null;
        }
    }

    public synchronized Map<String, Object> dismissRoll(Long stateId) {
        Map<String, Object> result = new LinkedHashMap<>();
        AutoRollState state = autoRollStateRepo.findById(stateId).orElse(null);
        if (state == null) { result.put("status", "ERROR"); result.put("message", "Auto-roll state not found"); return result; }
        state.setStatus("DISMISSED");
        state.setCurrentPositionId(null);
        state.setUpdatedAt(LocalDateTime.now());
        autoRollStateRepo.save(state);
        autoExecService.addLog("AUTO_ROLL", "DISMISSED", state.getUnderlying() + " roll proposal dismissed by user");
        result.put("status", "SUCCESS");
        return result;
    }

    private double fetchSpot(String underlying) {
        try {
            String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
            String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
            double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
            double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
            if (spot <= 0 && fut > 0) spot = fut;
            return spot;
        } catch (Exception e) {
            return 0;
        }
    }
}
