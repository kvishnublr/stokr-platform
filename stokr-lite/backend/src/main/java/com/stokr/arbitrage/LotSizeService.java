package com.stokr.arbitrage;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.broker.ZerodhaAdapter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Keeps OptionChainService's lot-size table current by pulling the real values from
 * Zerodha's own instrument dump, instead of a hardcoded table that silently goes stale
 * whenever NSE revises a lot size (which already happened once this session: NIFTY's real
 * lot size turned out to be 65, not the 25 that had been hardcoded -- every rupee figure
 * this platform computed for NIFTY was off by that factor until this was caught).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotSizeService {

    private final BrokerAccountRepository brokerAccountRepo;
    private final ZerodhaAdapter zerodhaAdapter;

    @PostConstruct
    public void init() {
        refresh();
    }

    /** Once daily before market open -- lot sizes don't change intraday, and NSE revisions
     *  are announced well in advance, so this cadence is more than fast enough to stay current. */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refresh() {
        try {
            BrokerAccount account = brokerAccountRepo.findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
                    .stream().findFirst().orElse(null);
            if (account == null) {
                log.debug("Lot-size refresh skipped: no active Zerodha account");
                return;
            }
            Map<String, Integer> lotSizes = zerodhaAdapter.fetchLotSizes(account.getAccessToken());
            if (lotSizes.isEmpty()) {
                log.warn("Lot-size refresh returned nothing -- keeping previous/fallback values");
                return;
            }
            OptionChainService.updateLotSizes(lotSizes);
            log.info("Refreshed lot sizes from Zerodha: NIFTY={} BANKNIFTY={} FINNIFTY={} MIDCPNIFTY={}",
                    lotSizes.get("NIFTY"), lotSizes.get("BANKNIFTY"), lotSizes.get("FINNIFTY"), lotSizes.get("MIDCPNIFTY"));
        } catch (Exception e) {
            log.warn("Lot-size refresh failed, keeping previous/fallback values: {}", e.getMessage());
        }
    }
}
