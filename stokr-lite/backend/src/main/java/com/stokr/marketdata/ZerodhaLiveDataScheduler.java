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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int    BATCH_SIZE     = 500;

    // RestTemplate with 10s connect + 15s read timeout — prevents scheduler thread hang
    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    private final Map<String, BigDecimal> ltpCache     = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> orbHighCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> orbLowCache  = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> prevLtpCache = new ConcurrentHashMap<>();
    private final Map<String, Long>       prevVolCache = new ConcurrentHashMap<>();

    // 500 unique NSE symbols — NIFTY 50 + NEXT 50 + MIDCAP 150 + SMALLCAP 250 (top liquid)
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
        // ── NIFTY MIDCAP 150 ──
        "ABB", "ACC", "ABCAPITAL", "ABFRL", "ADANIGREEN",
        "ALKEM", "APLLTD", "ASTRAL", "ATUL", "AUBANK",
        "BALKRISHNA", "BATAINDIA", "CANFINHOME", "CASTROLIND", "CEATLTD",
        "CONCOR", "COROMANDEL", "CROMPTON", "CUMMINSIND", "DEEPAKNTR",
        "DIXON", "DMART", "EMAMILTD", "ENDURANCE", "EQUITASBNK",
        "FINCABLES", "GMRINFRA", "GNFC", "GRANULES", "GSPL",
        "HFCL", "HONAUT", "IDFCFIRSTB", "IDFC", "IRFC",
        "JKCEMENT", "JSL", "JSWENERGY", "JUBLINGREA", "KAJARIACER",
        "KPIL", "LALPATHLAB", "LATENTVIEW", "LICHSGFIN", "LINDEINDIA",
        "LTTS", "MGL", "METROPOLIS", "MPHASIS", "MFSL",
        "NLCINDIA", "NOCIL", "OBEROIRLTY", "PERSISTENT", "PHOENIXLTD",
        "PIDILITIND", "PRESTIGE", "PVRINOX", "RADICO", "RAMCOCEM",
        "RELAXO", "ROUTE", "SANOFI", "SCHAEFFLER", "SHREECEM",
        "SJVN", "SKFINDIA", "SOBHA", "STARHEALTH", "SUNDARMFIN",
        "SUNDRMFAST", "SUPREMEIND", "SYNGENE", "TATACHEM", "TEAMLEASE",
        "TIINDIA", "TIMKEN", "TTKPRESTIG", "UBLHLDNG", "UNITDSPR",
        "AARTIIND", "APOLLOTYRE", "ASAHIINDIA", "ASHOKLEY", "BALRAMCHIN",
        "BAYERCROP", "BHARATFORG", "BHEL", "BLUESTARCO", "BSOFT",
        "CAMS", "CDSL", "CENTURYPLY", "CHAMBLFERT", "COCHINSHIP",
        "CREDITACC", "CYIENT", "DCMSHRIRAM", "DELTACORP", "ELGIEQUIP",
        "ENGINERSIN", "ESCOTEK", "FIVESTAR", "FLUOROCHEM", "GLENMARK",
        "GODREJIND", "GREENPLY", "GRINDWELL", "GTLINFRA", "GUJGASLTD",
        "HAPPSTMNDS", "HEIDELBERG", "HSCL", "HUDCO", "IBREALEST", "IIFL",
        // ── NIFTY SMALLCAP 250 (top 200 by liquidity) ──
        "AARTIDRUGS", "AIAENG", "AKZOINDIA", "AMARAJABAT", "ANGELONE",
        "ARVIND", "BAJAJCON", "BANKINDIA", "BBTC", "BEML",
        "BHARATELE", "BIKAJI", "BLS", "BRIGADE", "CAPLIPOINT",
        "CARYSIL", "CENTURYTEX", "CERA", "CHALET", "CHEMCON",
        "CLEAN", "CLNINDIA", "CRAFTSMAN", "DATAPATTNS", "DBREALTY",
        "DCBBANK", "DEEPAKFERT", "DEVYANI", "DHANUKA", "EDELWEISS",
        "EIDPARRY", "EPL", "ESTER", "ETHOS", "FAIRCHEMOR",
        "FLAIR", "GALAXY", "GARFIBRES", "GLS", "GMMPFAUDLR",
        "GPPL", "HLEGLAS", "HOMEFIRST", "IGARASHI", "INDIACEM",
        "INDIANB", "INDIGO", "INFIBEAM", "INTELLECT", "IPCALAB",
        "ITDCEM", "JBMA", "JBL", "JKIL", "JKTYRE",
        "JNKINDIA", "JYOTHYLAB", "KFINTECH", "KIRLOSENG", "KOLTEPATIL",
        "KRSNAA", "KSOLVES", "LAXMIMACH", "LEMONTREE", "MANAPPURAM",
        "MAPMYINDIA", "MARKSANS", "MASTEK", "MEDANTA", "MINDA",
        "MIRZAINT", "MOLDTKPAC", "NAVINFLUOR", "NIACL", "NOVARTIND",
        "NUVAMA", "OLECTRA", "OPTIEMUS", "PAYTM", "PGHH",
        "PNBHOUSING", "POLYMED", "POWERMECH", "RAJRATAN", "RATNAMANI",
        "REDINGTON", "SAPPHIRE", "SHYAMMETL", "SIGNATUREG", "SOLARA",
        "SPANDANA", "SUVENPHAR", "SWANENERGY", "TANLA", "TITAGARH",
        "TORNTPOWER", "TRIVENI", "USHAMART", "VAIBHAVGBL",
        // ── Additional liquid stocks ──
        "AFFLE", "ALKYLAMINE", "AMBER", "ATGL", "AVANTIFEED",
        "BASF", "CAMPUS", "CARTRADE", "CESC", "CIGNITITEC",
        "DALBHARAT", "DATAMATICS", "DEEPAKNI", "DLINKINDIA", "EASEMYTRIP",
        "ELECON", "EMCURE", "ESAB", "FACT", "GICRE",
        "GILLETTE", "GODFRYPHLP", "GRSE", "GSFC", "HBLPOWER",
        "HIKAL", "HINDCOPPER", "INDHOTEL", "INGERRAND", "JAMNAAUTO",
        "JAYASWALNES", "JBCHEPHARM", "JINDALSAW", "JMFINANCIL", "JUSTDIAL",
        "KPRMILL", "KRBL", "LINC", "LLOYDMETAL", "MAHINDCIE",
        "MAITHANALL", "MANGALAM", "MANINFRA", "MASFIN", "MOTHERSON",
        "NBCC", "NESCO", "NETWORK18", "NILKAMAL", "NUVOCO",
        "ORIENTELEC", "PAISALO", "PARADEEP", "PENIND", "PFIZER",
        "PNCINFRA", "PRICOLLTD", "PRINCEPIPE", "PRSMJOHNSN", "RAILVIKAS",
        "RAMCOIND", "RITES", "RPOWER", "SAFARI", "SANSERA",
        "SARDAEN", "SAREGAMA", "SBICARD", "SHRIRAMFIN", "SONATSOFTW",
        "SOUTHBANK", "SPARC", "SUZLON", "SWSOLAR", "SYMPHONY",
        "THERMAX", "THYROCARE", "TINPLATE", "TIPSMUSIC", "TRIDENT",
        "TV18BRDCST", "UCOBANK", "UJJIVANSFB", "UNIONBANK", "UTIAMC",
        "VGUARD", "VINATIORGA", "VOLTAMP", "VRLLOG", "WELCORP",
        "WELSPUNIND", "WONDERLA", "ZEEL", "ZENSARTECH", "BALMLAWRIE",
        "CESCLTD", "GNFC", "JSWHL", "KSCL", "LGHL",
        "RVNL", "SUZLON", "RAILTEL", "IREDA", "SJVN"
    );

    public static final List<String> NIFTY_50 = NIFTY_500.subList(0, 50);

    @Scheduled(cron = "0 */1 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledFetch() {
        try {
            fetchAndStoreQuotes();
        } catch (Exception e) {
            log.error("scheduledFetch error (will retry next minute): {}", e.getMessage());
        }
    }

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

        List<CandleData> candlesToSave = new ArrayList<>();

        List<List<String>> batches = partition(NIFTY_500, BATCH_SIZE);
        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<String> batch = batches.get(batchIdx);

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
                    log.warn("Zerodha quote API batch {} non-success", batchIdx);
                    continue;
                }

                JsonNode data = root.path("data");

                for (String symbol : batch) {
                    JsonNode q = data.path("NSE:" + symbol);
                    if (q.isMissingNode()) continue;

                    BigDecimal ltp     = bd(q, "last_price");
                    long totalVol      = q.path("volume").asLong(0);
                    JsonNode ohlc      = q.path("ohlc");
                    BigDecimal dayHigh = bd(ohlc, "high");
                    BigDecimal dayLow  = bd(ohlc, "low");
                    BigDecimal dayOpen = bd(ohlc, "open");

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

                    CandleData candle = new CandleData();
                    candle.setSymbol(symbol);
                    candle.setTimeframe("1min");
                    candle.setTimestamp(istNow);
                    candle.setOpen(candleOpen);
                    candle.setHigh(candleHigh);
                    candle.setLow(candleLow);
                    candle.setClose(ltp);
                    candle.setVolume(minuteVol);
                    candlesToSave.add(candle);
                }

            } catch (Exception e) {
                log.error("Zerodha quote fetch failed for batch {}: {}", batchIdx, e.getMessage());
            }
        }

        if (!candlesToSave.isEmpty()) {
            upsertCandles(candlesToSave);
        }

        log.info("Zerodha live data: stored {} 1-min candles at {} ({} batches)",
            candlesToSave.size(), istNow, batches.size());
    }

    /**
     * Batch upsert 1-min candles via JDBC with PostgreSQL native ON CONFLICT.
     * Bypasses Hibernate entirely so {@code GenerationType.IDENTITY} row-by-row
     * inserts and EntityManager state issues never cause duplicate-key failures.
     * <p>
     * {@code ON CONFLICT DO UPDATE} merges: close and volume are replaced,
     * high/low are widened — this is safe because the same cycle's data is
     * self-consistent (we never merge stale data from a different cycle).
     */
    private void upsertCandles(List<CandleData> candles) {
        String sql =
            "INSERT INTO candle_data (symbol, timeframe, \"timestamp\", \"open\", high, low, \"close\", volume, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (symbol, timeframe, \"timestamp\") DO UPDATE SET " +
            "  high    = GREATEST(candle_data.high, EXCLUDED.high), " +
            "  low     = LEAST(candle_data.low,    EXCLUDED.low), " +
            "  \"close\" = EXCLUDED.\"close\", " +
            "  volume  = EXCLUDED.volume " +
            "  /* open & timestamp are immutable after first insert */";

        List<Object[]> batchArgs = candles.stream()
            .map(c -> new Object[]{
                c.getSymbol(),
                c.getTimeframe(),
                c.getTimestamp(),
                c.getOpen(),
                c.getHigh(),
                c.getLow(),
                c.getClose(),
                c.getVolume()
            })
            .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Asia/Kolkata")
    public void cleanupOldCandles() {
        LocalDateTime cutoff = LocalDateTime.now(IST).minusDays(30);
        int deleted = candleDataRepository.deleteByTimestampBefore(cutoff);
        log.info("Candle cleanup: deleted {} rows older than 30 days", deleted);
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    public BigDecimal getLtp(String symbol) {
        return ltpCache.getOrDefault(symbol.toUpperCase(), BigDecimal.ZERO);
    }

    public BigDecimal getOrbHigh(String symbol) {
        return orbHighCache.get(symbol.toUpperCase());
    }

    public BigDecimal getOrbLow(String symbol) {
        return orbLowCache.get(symbol.toUpperCase());
    }

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
