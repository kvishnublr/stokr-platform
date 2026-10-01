package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import com.stokr.broker.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartStrategyExecutionService {

    private final LivePositionRepository positionRepo;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    private final OptionChainService optionChainService;

    public Map<String, Object> enterTrade(Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();

        String strategyType = (String) request.get("strategyType");
        String underlying = (String) request.get("underlying");
        String expiry = (String) request.get("expiry");
        int lots = request.get("lots") instanceof Number n ? n.intValue() : 1;
        String broker = (String) request.getOrDefault("broker", "PAPER");

        // DB Deduplication check
        boolean duplicateInDb = positionRepo.findAllOpen().stream().anyMatch(p ->
            "OPEN".equals(p.getStatus()) &&
            Objects.equals(p.getStrategyType(), strategyType) &&
            Objects.equals(p.getUnderlying(), underlying) &&
            (expiry == null || p.getExpiryDate() == null || p.getExpiryDate().toString().equals(expiry))
        );
        if (duplicateInDb) {
            log.warn("SMART_ENTRY blocked duplicate: {} {} {}", strategyType, underlying, expiry);
            result.put("status", "SKIPPED");
            result.put("message", "Duplicate active trade already exists for " + strategyType + " " + underlying);
            return result;
        }
        Double slPct = request.get("slPct") instanceof Number n ? n.doubleValue() : null;
        Double targetPct = request.get("targetPct") instanceof Number n ? n.doubleValue() : null;
        Integer timeExitMinutes = request.get("timeExitMinutes") instanceof Number n ? n.intValue() : null;
        double maxLoss = request.get("maxLoss") instanceof Number n ? n.doubleValue() : 0;
        double maxProfit = request.get("maxProfit") instanceof Number n ? n.doubleValue() : 0;
        String action = (String) request.get("action");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legList = (List<Map<String, Object>>) request.get("legList");
        if (legList == null || legList.isEmpty()) {
            result.put("status", "ERROR");
            result.put("message", "No legs provided");
            return result;
        }

        if (!isMarketOpen()) {
            result.put("status", "ERROR");
            result.put("message", "Market is closed. Orders can only be placed on trading days, 9:15 AM - 3:30 PM. "
                + "Off-hours results are LTP previews and will be re-priced from live bid/ask when the market opens.");
            return result;
        }

        if (broker != null && (broker.equalsIgnoreCase("MOTILAL") || broker.equalsIgnoreCase("MOTILAL_OSWAL") || broker.equalsIgnoreCase("MOTILAL OSWAL"))) {
            broker = "MOTILALOSWAL";
        }
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);
        int lotSize = OptionChainService.getLotSize(underlying);
        LocalDate expiryDate = expiry != null ? LocalDate.parse(expiry) : null;

        Long userId = null;
        BrokerAccount account = null;
        BrokerAdapter adapter = null;

        if (!isPaper) {
            try {
                List<BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
                if (accounts.isEmpty()) {
                    result.put("status", "ERROR");
                    result.put("message", "No active " + broker + " account found");
                    return result;
                }
                account = accounts.get(0);
                userId = account.getUserId();
                adapter = brokerService.getAdapter(broker);
            } catch (Exception e) {
                result.put("status", "ERROR");
                result.put("message", "Broker setup failed: " + e.getMessage());
                return result;
            }
        }

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .broker(broker)
                .underlying(underlying)
                .action(action)
                .strategyType(strategyType)
                .expiryDate(expiryDate)
                .lots(lots)
                .lotSize(lotSize)
                .slPct(slPct)
                .targetPct(targetPct)
                .timeExitMinutes(timeExitMinutes)
                .maxLossAmount(maxLoss * lots)
                .maxProfitAmount(maxProfit * lots)
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        if (isPaper) {
            List<Map<String, Object>> filledLegs = new ArrayList<>();
            double totalCost = 0;
            for (Map<String, Object> leg : legList) {
                Map<String, Object> filled = new LinkedHashMap<>(leg);
                double price = leg.get("price") instanceof Number n ? n.doubleValue() : 0;
                String side = (String) leg.get("side");
                int strike = ((Number) leg.get("strike")).intValue();
                String optType = (String) leg.get("optionType");
                String legExpiry = leg.get("expiry") instanceof String s ? s : (expiry != null ? expiry : "");
                LocalDate legExpiryDate = !legExpiry.isEmpty() ? LocalDate.parse(legExpiry) : expiryDate;
                String symbol = leg.get("symbol") instanceof String s ? s :
                    optionChainService.buildNfoSymbol(underlying, legExpiryDate, strike, optType);
                int qty = leg.get("qty") instanceof Number n ? n.intValue() : 1;

                filled.put("symbol", symbol);
                filled.put("entryPrice", price);
                filled.put("orderId", "PAPER_" + System.currentTimeMillis() + "_" + strike + optType);
                filledLegs.add(filled);

                double legCost = price * qty * lotSize * lots;
                totalCost += "BUY".equals(side) ? -legCost : legCost;
            }

            position.setLegs(filledLegs);
            position.setEntryCost(BigDecimal.valueOf(totalCost));
            position.setTargetEdge(BigDecimal.valueOf(Math.abs(totalCost)));
            position.setStatus("OPEN");
            positionRepo.save(position);

            log.info("SMART_ENTRY [PAPER]: {} {} — {} legs, lots={}, SL={}%, target={}%, timeExit={}min",
                strategyType, underlying, filledLegs.size(), lots,
                slPct != null ? slPct : "off", targetPct != null ? targetPct : "off",
                timeExitMinutes != null ? timeExitMinutes : "off");

            result.put("status", "SUCCESS");
            result.put("positionId", position.getId());
            result.put("message", strategyType + " " + underlying + " entered PAPER — " + filledLegs.size() + " legs");
            result.put("broker", "PAPER");
            return result;
        }

        // LIVE execution
        try {
            List<Map<String, Object>> filledLegs = new ArrayList<>();
            double totalCost = 0;

            for (Map<String, Object> leg : legList) {
                int strike = ((Number) leg.get("strike")).intValue();
                String optType = (String) leg.get("optionType");
                String side = (String) leg.get("side");
                String legExpiry = leg.get("expiry") instanceof String s ? s : (expiry != null ? expiry : "");
                LocalDate legExpiryDate = !legExpiry.isEmpty() ? LocalDate.parse(legExpiry) : expiryDate;
                String symbol = optionChainService.buildNfoSymbol(underlying, legExpiryDate, strike, optType);
                int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                int qty = lots * lotSize * qtyMult;
                double price = leg.get("price") instanceof Number n ? n.doubleValue() : 0;

                // Cap live order slippage buffer to max 1% or Rs 3.00 to protect against wide spreads on heavy options
                double bufAmt = Math.min(price * 0.01, 3.0);
                double bufferedPrice = price > 0
                    ? ("BUY".equals(side) ? Math.ceil((price + bufAmt) * 20) / 20.0
                                          : Math.floor((price - bufAmt) * 20) / 20.0)
                    : 0.0;

                BrokerOrderRequest req = BrokerOrderRequest.builder()
                    .symbol(symbol).exchange("NFO")
                    .side("BUY".equals(side) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL)
                    .quantity(qty).price(bufferedPrice)
                    .orderType(bufferedPrice > 0 ? BrokerOrderRequest.OrderType.LIMIT : BrokerOrderRequest.OrderType.MARKET)
                    .productType("NRML").build();

                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);

                Map<String, Object> filled = new LinkedHashMap<>(leg);
                filled.put("symbol", symbol);
                filled.put("entryPrice", price);
                filled.put("orderId", resp.orderId());

                if (!resp.isSuccess()) {
                    log.warn("SMART_ENTRY leg failed: {} {} {}: {}", side, symbol, strike, resp.message());
                    position.setStatus("FAILED");
                    position.setErrorMessage(side + " " + symbol + ": " + resp.message());
                    position.setLegs(filledLegs);
                    positionRepo.save(position);
                    result.put("status", "ERROR");
                    result.put("message", "Leg failed: " + resp.message());
                    return result;
                }

                filledLegs.add(filled);
                double legCost = price * qtyMult * lotSize * lots;
                totalCost += "BUY".equals(side) ? -legCost : legCost;
            }

            position.setLegs(filledLegs);
            position.setEntryCost(BigDecimal.valueOf(totalCost));
            position.setTargetEdge(BigDecimal.valueOf(Math.abs(totalCost)));
            position.setStatus("OPEN");
            positionRepo.save(position);

            log.info("SMART_ENTRY [LIVE/{}]: {} {} — {} legs, lots={}",
                broker, strategyType, underlying, filledLegs.size(), lots);

            result.put("status", "SUCCESS");
            result.put("positionId", position.getId());
            result.put("message", strategyType + " " + underlying + " entered LIVE via " + broker);
            result.put("broker", broker);
            return result;

        } catch (Exception e) {
            log.error("SMART_ENTRY failed: {}", e.getMessage());
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            result.put("status", "ERROR");
            result.put("message", "Entry failed: " + e.getMessage());
            return result;
        }
    }

    public List<Map<String, Object>> getActivePositions() {
        List<String> smartTypes = List.of(
            "ADAPTIVE",
            "BROKEN_WING_BUTTERFLY", "RATIO_BUTTERFLY", "SKEW_HARVEST",
            "EXPIRY_THETA_CRUSH", "BOX_SPREAD_ARB", "JADE_LIZARD", "CALENDAR_SPREAD_EDGE",
            "IRON_CONDOR", "MORNING_RANGE_THETA"
        );
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        java.time.LocalTime nowTime = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean isPast330 = nowTime.isAfter(java.time.LocalTime.of(15, 30));

        List<LivePosition> openPositions = positionRepo.findAllOpen().stream()
            .filter(p -> "OPEN".equals(p.getStatus()) && smartTypes.contains(p.getStrategyType()))
            .filter(p -> p.getExpiryDate() == null || !p.getExpiryDate().isBefore(today))
            .filter(p -> p.getExpiryDate() == null || !(p.getExpiryDate().isEqual(today) && isPast330))
            .toList();

        if (openPositions.isEmpty()) return List.of();

        List<String> symbols = new ArrayList<>();
        for (LivePosition p : openPositions) {
            if (p.getLegs() != null) {
                for (Map<String, Object> leg : p.getLegs()) {
                    Object sym = leg.get("symbol");
                    if (sym instanceof String s) symbols.add(s);
                }
            }
        }

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.debug("Smart active positions quote fetch failed: {}", e.getMessage());
            quotes = Map.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (LivePosition p : openPositions) {
            Map<String, Object> map = p.toMap();
            double pnl = computeMultiLegPnl(p, quotes);
            double roundedPnl = Math.round(pnl * 100.0) / 100.0;
            map.put("currentPnl", roundedPnl);
            map.put("pnl", roundedPnl);

            List<Map<String, Object>> legs = p.getLegs();
            if (legs != null) {
                List<Map<String, Object>> updatedLegs = new ArrayList<>();
                for (Map<String, Object> leg : legs) {
                    Map<String, Object> legMap = new LinkedHashMap<>(leg);
                    String symbol = (String) leg.get("symbol");
                    OptionChainService.OptionQuote q = symbol != null ? quotes.get(symbol) : null;
                    if (q != null && q.lastPrice > 0) {
                        double lp = Math.round(q.lastPrice * 100.0) / 100.0;
                        legMap.put("currentPrice", lp);
                        legMap.put("lastPrice", lp);
                    }
                    updatedLegs.add(legMap);
                }
                map.put("legs", updatedLegs);
            }
            result.add(map);
        }
        return result;
    }

    public Map<String, Object> exitPosition(Long positionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        LivePosition pos = positionRepo.findById(positionId).orElse(null);
        if (pos == null) {
            result.put("status", "ERROR");
            result.put("message", "Position not found");
            return result;
        }
        if (!"OPEN".equals(pos.getStatus())) {
            result.put("status", "ERROR");
            result.put("message", "Position is " + pos.getStatus() + ", not OPEN");
            return result;
        }

        boolean isPaper = pos.getBroker() == null || "PAPER".equalsIgnoreCase(pos.getBroker());

        List<String> symbols = new ArrayList<>();
        if (pos.getLegs() != null) {
            for (Map<String, Object> leg : pos.getLegs()) {
                Object sym = leg.get("symbol");
                if (sym instanceof String s) symbols.add(s);
            }
        }

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            quotes = Map.of();
        }

        double pnl = computeMultiLegPnl(pos, quotes);

        if (isPaper) {
            pos.setStatus("CLOSED");
            pos.setExitReason("MANUAL");
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            pos.setExitedAt(LocalDateTime.now());
            positionRepo.save(pos);
            result.put("status", "SUCCESS");
            result.put("pnl", pnl);
            result.put("message", "Position closed (PAPER) — P&L ₹" + String.format("%.0f", pnl));
            return result;
        }

        // LIVE exit
        try {
            BrokerAccount account = brokerAccountRepo.findByBrokerNameAndStatus(pos.getBroker(), "ACTIVE").get(0);
            BrokerAdapter adapter = brokerService.getAdapter(pos.getBroker());

            for (Map<String, Object> leg : pos.getLegs()) {
                String symbol = (String) leg.get("symbol");
                String side = (String) leg.get("side");
                int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                int qty = (pos.getLots() != null ? pos.getLots() : 1) * pos.getLotSize() * qtyMult;

                BrokerOrderRequest.Side exitSide = "BUY".equals(side)
                    ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;

                BrokerOrderRequest req = BrokerOrderRequest.builder()
                    .symbol(symbol).exchange("NFO")
                    .side(exitSide).quantity(qty)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("NRML").build();

                adapter.placeOrder(account.getAccessToken(), req);
            }

            pos.setStatus("CLOSED");
            pos.setExitReason("MANUAL");
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            pos.setExitedAt(LocalDateTime.now());
            positionRepo.save(pos);

            result.put("status", "SUCCESS");
            result.put("pnl", pnl);
            result.put("message", "Position closed LIVE — P&L ₹" + String.format("%.0f", pnl));
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Exit failed: " + e.getMessage());
        }
        return result;
    }

    double computeMultiLegPnl(LivePosition pos, Map<String, OptionChainService.OptionQuote> quotes) {
        List<Map<String, Object>> legs = pos.getLegs();
        if (legs == null) return 0;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : 75;
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        double pnl = 0;
        for (Map<String, Object> leg : legs) {
            String symbol = (String) leg.get("symbol");
            String side = (String) leg.get("side");
            double entryPrice = leg.get("entryPrice") instanceof Number n ? n.doubleValue()
                : (leg.get("price") instanceof Number n2 ? n2.doubleValue() : 0);
            int qty = leg.get("qty") instanceof Number n ? n.intValue() : 1;

            OptionChainService.OptionQuote q = symbol != null ? quotes.get(symbol) : null;
            double currentPrice = 0;
            if (q != null) {
                if ("BUY".equals(side)) {
                    currentPrice = q.bid > 0 ? q.bid : q.lastPrice;
                } else {
                    currentPrice = q.ask > 0 ? q.ask : q.lastPrice;
                }
            }

            boolean isPaper = pos.getBroker() == null || "PAPER".equalsIgnoreCase(pos.getBroker());
            double evalPrice = currentPrice;
            if (isPaper && q != null && q.lastPrice > 0) {
                evalPrice = q.lastPrice;
            }
            double legPnl;
            if ("BUY".equals(side)) {
                legPnl = (evalPrice - entryPrice) * qty * lotSize * lots;
            } else {
                legPnl = (entryPrice - evalPrice) * qty * lotSize * lots;
            }
            pnl += legPnl;
        }
        return pnl;
    }

    private boolean isMarketOpen() {
        return com.stokr.marketdata.MarketCalendar.isMarketOpenNow();
    }
}
