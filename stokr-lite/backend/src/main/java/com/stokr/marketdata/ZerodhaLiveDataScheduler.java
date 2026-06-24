package com.stokr.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches live quotes from Zerodha every minute and stores 1-min candles to DB.
 * Also maintains in-memory LTP and ORB caches used by exits and strategy evaluation.
 *
 * Called synchronously by ExecutionEngine.runScanCycle() so data is always fresh
 * before the strategy scan runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaLiveDataScheduler {

    private final BrokerAccountRepository brokerAccountRepository;
    private final CandleDataRepository candleDataRepository;

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private static final String KITE_API_BASE  = "https://api.kite.trade";
    private static final ZoneId IST            = ZoneId.of("Asia/Kolkata");
    private static final int    BATCH_SIZE     = 150;  // Zerodha GET URL limit
    private static final long   BATCH_DELAY_MS = 400;  // 3 req/sec = 333ms min

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // LTP cache — updated every minute
    private final Map<String, BigDecimal> ltpCache       = new ConcurrentHashMap<>();
    // ORB levels — snapshotted at 9:30 IST each day
    private final Map<String, BigDecimal> orbHighCache   = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> orbLowCache    = new ConcurrentHashMap<>();
    // Previous values needed for per-minute candle construction
    private final Map<String, BigDecimal> prevLtpCache   = new ConcurrentHashMap<>();
    private final Map<String, Long>       prevVolCache   = new ConcurrentHashMap<>();

    // NIFTY 500 — batched across 4 API calls (150 symbols each, 400ms apart)
    public static final List<String> NIFTY_500 = List.of(
        // ── NIFTY 50 ──
        "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK",
        "SBIN", "BHARTIARTL", "ITC", "KOTAKBANK", "LT",
        "HINDUNILVR", "AXISBANK", "MARUTI", "BAJFINANCE", "ASIANPAINT",
        "SUNPHARMA", "TITAN", "ULTRACEMCO", "WIPRO", "HCLTECH",
        "TATAMOTORS", "ONGC", "NTPC", "POWERGRID", "ADANIPORTS",
        "JSWSTEEL", "TATASTEEL", "COALINDIA", "M&M", "TECHM",
        "BAJAJFINSV", "BAJAJ-AUTO", "DRREDDY", "CIPLA", "DIVISLAB",
        "EICHERMOT", "GRASIM", "HDFCLIFE", "HEROMOTOCO", "HINDALCO",
        "INDUSINDBK", "NESTLEIND", "SBILIFE", "TATACONSUM", "APOLLOHOSP",
        "BPCL", "BRITANNIA", "LTIM", "UPL", "VEDL",
        // ── NIFTY NEXT 50 ──
        "ADANIENT", "AMBUJACEM", "AUROPHARMA", "BANDHANBNK", "BANKBARODA",
        "BEL", "BERGEPAINT", "BIOCON", "BOSCHLTD", "CANBK",
        "COLPAL", "DABUR", "DLF", "ESCORTS", "EXIDEIND",
        "FEDERALBNK", "GAIL", "GODREJCP", "GODREJPROP", "HAVELLS",
        "HINDPETRO", "ICICIGI", "ICICIPRULI", "INDIAMART", "INDUSTOWER",
        "IOC", "IGL", "IRCTC", "JUBLFOOD", "LICI",
        "LUPIN", "MARICO", "MUTHOOTFIN", "NMDC", "OFSS",
        "PAGEIND", "PEL", "PETRONET", "PIIND", "POLYCAB",
        "SIEMENS", "SRF", "TATAPOWER", "TORNTPHARM", "VOLTAS",
        "BAJAJHLDNG", "CHOLAFIN", "NAUKRI", "RECLTD", "TRENT",
        // ── NIFTY MIDCAP 150 (top 100 by liquidity) ──
        "ABB", "ACC", "ABCAPITAL", "ABFRL", "ADANIGREEN",
        "ADANIPORTS", "ALKEM", "APLLTD", "ASTRAL", "ATUL",
        "AUBANK", "BALKRISHNA", "BATAINDIA", "CANFINHOME", "CASTROLIND",
        "CEATLTD", "CONCOR", "COROMANDEL", "CROMPTON", "CUMMINSIND",
        "DEEPAKNTR", "DIXON", "DMART", "EMAMILTD", "ENDURANCE",
        "EQUITASBNK", "FINCABLES", "GMRINFRA", "GNFC", "GRANULES",
        "GSPL", "HFCL", "HONAUT", "IDFCFIRSTB", "IDFC",
        "IRFC", "JKCEMENT", "JSL", "JSWENERGY", "JUBLINGREA",
        "KAJARIACER", "KPIL", "LALPATHLAB", "LATENTVIEW", "LICHSGFIN",
        "LINDEINDIA", "LTTS", "MGL", "METROPOLIS", "MINDTREE",
        "MPHASIS", "MFSL", "NAUKRI", "NLCINDIA", "NOCIL",
        "OBEROIRLTY", "OFSS", "PERSISTENT", "PHOENIXLTD", "PIDILITIND",
        "PRESTIGE", "PVRINOX", "RADICO", "RAMCOCEM", "RELAXO",
        "ROUTE", "SANOFI", "SCHAEFFLER", "SHREECEM", "SJVN",
        "SKFINDIA", "SOBHA", "STARHEALTH", "SUNDARMFIN", "SUNDRMFAST",
        "SUPREMEIND", "SYNGENE", "TATACHEM", "TCNSBRANDS", "TEAMLEASE",
        "TIINDIA", "TIMKEN", "TTKPRESTIG", "UBLHLDNG", "UNITDSPR",
        "AARTIIND", "APOLLOTYRE", "ASAHIINDIA", "ASHOKLEY", "BALRAMCHIN",
        "BAYERCROP", "BHARATFORG", "BHEL", "BLUESTARCO", "BSOFT",
        "CAMS", "CANFINHOME", "CDSL", "CENTURYPLY", "CHAMBLFERT",
        "COCHINSHIP", "CREDITACC", "CYIENT", "DCMSHRIRAM", "DELTACORP",
        "EICHERMOT", "ELGIEQUIP", "ENGINERSIN", "ESCORTS", "ESCOTEK",
        "FIVESTAR", "FLUOROCHEM", "GLENMARK", "GODREJIND", "GREENPLY",
        "GRINDWELL", "GTLINFRA", "GUJGASLTD", "HAPPSTMNDS", "HEIDELBERG",
        "HEROMOTOCO", "HSCL", "HUDCO", "IBREALEST", "IIFL",
        // ── NIFTY SMALLCAP 100 (most liquid) ──
        "AARTIDRUGS", "AIAENG", "AKZOINDIA", "AMARAJABAT", "ANGELONE",
        "ARVIND", "ASIANENE", "BAJAJCON", "BALARAMCHIN", "BANKINDIA",
        "BBTC", "BEML", "BHARATELE", "BIKAJI", "BLS",
        "BRIGADE", "CAPLIPOINT", "CARYSIL", "CENTURYTEX", "CERA",
        "CHALET", "CHEMCON", "CLEAN", "CLNINDIA", "CONFIPET",
        "CRAFTSMAN", "DATAPATTNS", "DBREALTY", "DCBBANK", "DEEPAKFERT",
        "DEVYANI", "DHANI", "DHANUKA", "EDELWEISS", "EIDPARRY",
        "EPL", "ESTER", "ETHOS", "FAIRCHEMOR", "FLAIR",
        "GALAXY", "GARFIBRES", "GLS", "GMMPFAUDLR", "GPPL",
        "HARSHA", "HLEGLAS", "HOMEFIRST", "IGARASHI", "INDIACEM",
        "INDIANB", "INDIGO", "INFIBEAM", "INTELLECT", "IPCALAB",
        "ITDCEM", "JAYASWALNES", "JBMA", "JBL", "JKIL",
        "JKTYRE", "JNKINDIA", "JYOTHYLAB", "KFINTECH", "KIRLOSENG",
        "KOLTEPATIL", "KRSNAA", "KSOLVES", "LAXMIMACH", "LEMONTREE",
        "MANAPPURAM", "MAPMYINDIA", "MARKSANS", "MASTEK", "MEDANTA",
        "MGLAMB", "MINDA", "MIRZAINT", "MOLDTKPAC", "NAVINFLUOR",
        "NIACL", "NOVARTIND", "NUVAMA", "OLECTRA", "OPTIEMUS",
        "PAYTM", "PGHH", "PNBHOUSING", "POLYMED", "POWERMECH",
        "RAJRATAN", "RATNAMANI", "RECLTD", "REDINGTON", "SAPPHIRE",
        "SHYAMMETL", "SICAL", "SIGNATUREG", "SOBHA", "SOLARA",
        "SPANDANA", "SUVENPHAR", "SWANENERGY", "TANLA", "TITAGARH",
        "TORNTPOWER", "TRIVENI", "USHAMART", "VAIBHAVGBL", "VIJAYABANK"
    );

    // Keep backward compat — callers using NIFTY_50 still work
    public static final List<String> NIFTY_50 = NIFTY_500.subList(0, 50);

    /**
     * Called by ExecutionEngine before every scan cycle.
     * Fetches Zerodha /quote for NIFTY 500 in batches of 150 (400ms between batches)
     * and stores 1-min candles to DB.
     */
    public void fetchAndStoreQuotes() {
        String accessToken = resolveToken();
        if (accessToken == null) {
            log.warn("Zerodha token unavailable — skipping live data fetch");
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + zerodhaApiKey + ":" + accessToken);
        headers.set("X-Kite-Version", "3");

        LocalDateTime istNow = LocalDateTime.now(IST).withSecond(0).withNano(0);
        boolean isOrbSnapshotTime = istNow.getHour() == 9 && istNow.getMinute() == 30;
        int totalProcessed = 0;

        // Process in batches of BATCH_SIZE to stay within Zerodha URL length limit
        List<List<String>> batches = partition(NIFTY_500, BATCH_SIZE);
        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<String> batch = batches.get(batchIdx);

            // 400ms delay between batches (Zerodha rate limit: 3 req/sec)
            if (batchIdx > 0) {
                try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ignored) {}
            }

            StringBuilder url = new StringBuilder(KITE_API_BASE + "/quote?");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) url.append("&");
                url.append("i=NSE:").append(batch.get(i));
            }

            try {
                ResponseEntity<String> resp = restTemplate.exchange(
                    url.toString(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);

                JsonNode root = mapper.readTree(resp.getBody());
                if (!"success".equals(root.path("status").asText())) {
                    log.warn("Zerodha quote API batch {} non-success: {}", batchIdx, resp.getBody());
                    continue;
                }

                JsonNode data = root.path("data");
                Instant ts = istNow.atZone(IST).toInstant();

                for (String symbol : batch) {
                    JsonNode q = data.path("NSE:" + symbol);
                    if (q.isMissingNode()) continue;

                    BigDecimal ltp    = bd(q, "last_price");
                    long totalVol     = q.path("volume").asLong(0);

                    JsonNode ohlc     = q.path("ohlc");
                    BigDecimal dayOpen = bd(ohlc, "open");
                    BigDecimal dayHigh = bd(ohlc, "high");
                    BigDecimal dayLow  = bd(ohlc, "low");

                    ltpCache.put(symbol, ltp);

                    long prevVol   = prevVolCache.getOrDefault(symbol, 0L);
                    long minuteVol = Math.max(0, totalVol - prevVol);
                    prevVolCache.put(symbol, totalVol);

                    BigDecimal prevClose  = prevLtpCache.getOrDefault(symbol, dayOpen);
                    BigDecimal candleOpen = prevClose;
                    BigDecimal candleHigh = ltp.max(prevClose);
                    BigDecimal candleLow  = ltp.min(prevClose);
                    prevLtpCache.put(symbol, ltp);

                    if (isOrbSnapshotTime) {
                        orbHighCache.put(symbol, dayHigh);
                        orbLowCache.put(symbol, dayLow);
                    }

                    CandleData candle = candleDataRepository
                        .findBySymbolAndTimeframeAndTimestamp(symbol, "1min", ts)
                        .orElseGet(CandleData::new);
                    candle.setSymbol(symbol);
                    candle.setTimeframe("1min");
                    candle.setTimestamp(ts);
                    candle.setOpen(candleOpen);
                    candle.setHigh(candleHigh);
                    candle.setLow(candleLow);
                    candle.setClose(ltp);
                    candle.setVolume(minuteVol);
                    candleDataRepository.save(candle);
                    totalProcessed++;
                }

            } catch (Exception e) {
                log.error("Zerodha quote fetch failed for batch {}: {}", batchIdx, e.getMessage());
            }
        }

        log.info("Zerodha live data: stored {} 1-min candles at {} ({} batches)",
            totalProcessed, istNow, batches.size());
    }

    /** Nightly cleanup: delete candles older than 30 days to keep DB lean. */
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Asia/Kolkata")
    public void cleanupOldCandles() {
        Instant cutoff = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        int deleted = candleDataRepository.deleteByTimestampBefore(cutoff);
        log.info("Candle cleanup: deleted {} rows older than 30 days", deleted);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    // ── Cache accessors used by BrokerMarketDataService + SignalProcessor ──

    public BigDecimal getLtp(String symbol) {
        return ltpCache.getOrDefault(symbol.toUpperCase(), BigDecimal.ZERO);
    }

    public BigDecimal getOrbHigh(String symbol) {
        return orbHighCache.get(symbol.toUpperCase());
    }

    public BigDecimal getOrbLow(String symbol) {
        return orbLowCache.get(symbol.toUpperCase());
    }

    /** True when a non-expired Zerodha token is available. */
    public boolean isHealthy() {
        return resolveToken() != null;
    }

    public String getHealthStatus() {
        List<BrokerAccount> accounts = brokerAccountRepository
            .findByBrokerNameAndStatus("ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) return "NO_ACCOUNT";
        boolean anyValid = accounts.stream().anyMatch(a -> !a.isTokenExpired());
        return anyValid ? "OK" : "TOKEN_EXPIRED";
    }

    // ── Helpers ──

    private String resolveToken() {
        if (zerodhaApiKey == null || zerodhaApiKey.isBlank()) return null;
        return brokerAccountRepository
            .findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
            .stream()
            .filter(a -> !a.isTokenExpired() && a.getAccessToken() != null)
            .findFirst()
            .map(BrokerAccount::getAccessToken)
            .orElse(null);
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        return new BigDecimal(n.asText());
    }
}
