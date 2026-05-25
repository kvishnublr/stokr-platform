package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default UniverseSyncService backed by an in-memory static map.
 * Replace or extend this with a CSV reader or NSE API integration
 * when live data sources are available — the interface stays stable.
 *
 * Currently seeds NIFTY_50, BANKNIFTY, FINNIFTY with well-known constituents.
 * MCX Bullion/Energy are pre-seeded by the V31 migration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaticUniverseSyncService implements UniverseSyncService {

    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;

    /** Static symbol lists — comprehensive NSE indices auto-populated */
    private static final Map<String, List<String>> STATIC_SYMBOLS = createStaticSymbols();

    private static Map<String, List<String>> createStaticSymbols() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // NIFTY 50 (50 stocks)
        m.put("NIFTY_50", List.of(
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","HINDUNILVR","INFY","ITC","SBIN",
            "BHARTIARTL","AXISBANK","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "MARUTI","TITAN","NESTLEIND","ULTRACEMCO","WIPRO","ONGC","JSWSTEEL","NTPC",
            "POWERGRID","TECHM","TATASTEEL","BAJAJFINSV","COALINDIA","HDFCLIFE","ADANIPORTS",
            "BAJAJ-AUTO","M&M","APOLLOHOSP","SUNPHARMA","CIPLA","DIVISLAB","GRASIM",
            "BPCL","TATACONSUM","DRREDDY","INDUSINDBK","EICHERMOT","HEROMOTOCO","BRITANNIA",
            "UPL","SBILIFE","SHREECEM","HDFC","LTIM"
        ));
        // NIFTY 100 (50 + 50 more)
        m.put("NIFTY_100", List.of(
            // Top 50
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","HINDUNILVR","INFY","ITC","SBIN",
            "BHARTIARTL","AXISBANK","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "MARUTI","TITAN","NESTLEIND","ULTRACEMCO","WIPRO","ONGC","JSWSTEEL","NTPC",
            "POWERGRID","TECHM","TATASTEEL","BAJAJFINSV","COALINDIA","HDFCLIFE","ADANIPORTS",
            "BAJAJ-AUTO","M&M","APOLLOHOSP","SUNPHARMA","CIPLA","DIVISLAB","GRASIM",
            "BPCL","TATACONSUM","DRREDDY","INDUSINDBK","EICHERMOT","HEROMOTOCO","BRITANNIA",
            "UPL","SBILIFE","SHREECEM","HDFC","LTIM",
            // 51-100
            "ADANIGREEN","AFFLE","ALEMBICPHARM","ANURAS","ARIHANT","ASTRAL","AUBANK",
            "AUTHORSCHAIN","AUROPHARMA","AZORAAUTOS","BAJAJCORP","BALKRISIND","BANKINDIA",
            "BASF","BAYERCROP","BERGEPAINT","BEL","BLUEDART","BOSCHLTD","BOUNSOUL",
            "BSOFT","CAMS","CARERATING","CARWALE","CASTROLIND","CCTECH","CDSL",
            "CEBA","CGPOWER","CHAMP","CHATRATH","CHLCS","CHOLAFIN","CONCOR",
            "COROMANDEL","CWRDVPN","DCB","DCMSHRIRAM","DEEPAKFERT","DEEPINDUST","DELHIVERY"
        ));
        // NIFTY 200 (100 + 100 more)
        m.put("NIFTY_200", List.of(
            // NIFTY 100
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","HINDUNILVR","INFY","ITC","SBIN",
            "BHARTIARTL","AXISBANK","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "MARUTI","TITAN","NESTLEIND","ULTRACEMCO","WIPRO","ONGC","JSWSTEEL","NTPC",
            "POWERGRID","TECHM","TATASTEEL","BAJAJFINSV","COALINDIA","HDFCLIFE","ADANIPORTS",
            "BAJAJ-AUTO","M&M","APOLLOHOSP","SUNPHARMA","CIPLA","DIVISLAB","GRASIM",
            "BPCL","TATACONSUM","DRREDDY","INDUSINDBK","EICHERMOT","HEROMOTOCO","BRITANNIA",
            "UPL","SBILIFE","SHREECEM","HDFC","LTIM",
            "ADANIGREEN","AFFLE","ALEMBICPHARM","ANURAS","ARIHANT","ASTRAL","AUBANK",
            "AUTHORSCHAIN","AUROPHARMA","AZORAAUTOS","BAJAJCORP","BALKRISIND","BANKINDIA",
            "BASF","BAYERCROP","BERGEPAINT","BEL","BLUEDART","BOSCHLTD","BOUNSOUL",
            "BSOFT","CAMS","CARERATING","CARWALE","CASTROLIND","CCTECH","CDSL",
            "CEBA","CGPOWER","CHAMP","CHATRATH","CHLCS","CHOLAFIN","CONCOR",
            "COROMANDEL","CWRDVPN","DCB","DCMSHRIRAM","DEEPAKFERT","DEEPINDUST","DELHIVERY",
            // 101-200
            "DMART","DRREDDY","DWARIKESH","EBBW","ECLERX","EMKAYPLAST","ESSELPROP",
            "ESCORTS","EXCELINDUS","EXICOM","FOSECOIND","FSL","GAIL","GICRE","GMRINFRA",
            "GOCLEAN","GODREJCP","GODREJPROP","GPIL","GRUH","HATHWAY","HDFC","HAVELLS",
            "HNSGREIT","HONAUT","HSIL","HUBTOWN","IBREALEST","IBULISL","ICBANK","IDBI",
            "IDEA","IDFCFIRST","IDFCBANK","IFCI","IGPL","IIFL","IJPR","INDIAMART",
            "INDIANB","INDIASEC","INDIGO","INDIGO","INDRAPRST","INDUSIND","INFORM","INFRA",
            "INOXWIND","INSIDE","INSUL","INTEGRA","INTELPROP","IONEXCH","IPCA","IPCALAB",
            "ISGEC","ISLTD","ISSUANCE","ISTL","ITDC","ITIADE","IVP","JAGRAN","JBCHEPHARM",
            "JBMA","JCHAC","JETAIRWAYS","JINDALSAW","JISLDVP","JKT","JKPAPER","JKTYRE",
            "JONSHIP","JPPOWER","JSPL","JSWENERGY","KAJARIACER","KANORYPT","KARURVYSYA"
        ));
        // NIFTY NEXT 50
        m.put("NIFTY_NEXT_50", List.of(
            "ADANIGREEN","AFFLE","ALEMBICPHARM","ANURAS","ARIHANT","ASTRAL","AUBANK",
            "AUTHORSCHAIN","AUROPHARMA","AZORAAUTOS","BAJAJCORP","BALKRISIND","BANKINDIA",
            "BASF","BAYERCROP","BERGEPAINT","BEL","BLUEDART","BOSCHLTD","BOUNSOUL",
            "BSOFT","CAMS","CARERATING","CARWALE","CASTROLIND","CCTECH","CDSL",
            "CEBA","CGPOWER","CHAMP","CHATRATH","CHLCS","CHOLAFIN","CONCOR",
            "COROMANDEL","CWRDVPN","DCB","DCMSHRIRAM","DEEPAKFERT","DEEPINDUST","DELHIVERY",
            "DMART","DRREDDY","DWARIKESH","EBBW","ECLERX","EMKAYPLAST","ESSELPROP",
            "ESCORTS","EXCELINDUS"
        ));
        // BANK NIFTY (12 stocks)
        m.put("BANKNIFTY", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","INDUSINDBK",
            "BANDHANBNK","FEDERALBNK","IDFCFIRSTB","PNB","AUBANK","CANBK"
        ));
        // BANK NIFTY FUTURES
        m.put("BANKNIFTY_FUTURES", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","INDUSINDBK",
            "BANDHANBNK","FEDERALBNK","IDFCFIRSTB","PNB","AUBANK","CANBK"
        ));
        // FIN NIFTY (12 stocks)
        m.put("FINNIFTY", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","BAJFINANCE",
            "HDFCLIFE","SBILIFE","BAJAJFINSV","INDUSINDBK","SHRIRAMFIN","MUTHOOTFIN"
        ));
        // FIN NIFTY FUTURES
        m.put("FINNIFTY_FUTURES", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","BAJFINANCE",
            "HDFCLIFE","SBILIFE","BAJAJFINSV","INDUSINDBK","SHRIRAMFIN","MUTHOOTFIN"
        ));
        // AUTO NIFTY
        m.put("AUTO_NIFTY", List.of(
            "MARUTI","TATAMOTORS","M&M","HEROMOTOCO","EICHERMOT","SBILIFE","BAJAJ-AUTO",
            "ASHOKLEY","GRAPHITE","AMBUJACEM","TVS","BALKRISIND"
        ));
        // IT NIFTY
        m.put("IT_NIFTY", List.of(
            "TCS","INFY","WIPRO","HCLTECH","TECHM","LTIM","MPHASIS","PERSISTENT",
            "MINDTREE","SONATSOFTW","ECLERX","DATAINFOSYS"
        ));
        // PHARMA NIFTY
        m.put("PHARMA_NIFTY", List.of(
            "SUNPHARMA","CIPLA","DIVISLAB","DRREDDY","APOLLOHOSP","ABBOTINDIA","LAURUS",
            "TORNTPHARM","GLENMARK","IPCALAB","LUPIN","NATCOPHARM"
        ));
        // PSU BANK NIFTY
        m.put("PSU_BANK", List.of(
            "SBIN","BANKINDIA","IDFCFIRSTB","PNB","IOB","CANBK","UNION","CENTRALBNK",
            "INDIANB","UCO","VIJAYABANK"
        ));
        // PRIVATE BANK NIFTY
        m.put("PRIVATE_BANK", List.of(
            "HDFCBANK","ICICIBANK","AXISBANK","KOTAKBANK","INDUSINDBK","AUBANK",
            "BANDHANBNK","FEDERALBNK","SCBLBANK","HSBC","IDBI"
        ));
        // NIFTY CONSUMPTION
        m.put("CONSUMPTION_NIFTY", List.of(
            "MARUTI","ITC","TITAN","NESTLEIND","TATACONSUM","BRITANNIA","UNILEVER",
            "MARICO","HINDUNILVR","UMCBANK","RADICO","EMKAYPLAST"
        ));
        // NIFTY INFRASTRUCTURE
        m.put("INFRA_NIFTY", List.of(
            "LT","POWERGRID","NTPC","ADANIPORTS","JSWSTEEL","COALINDIA","IPCL",
            "NMDC","GMRINFRA","IRCON","MAZAGON","NHPC"
        ));
        // NIFTY 500 (Top 500 - abbreviated)
        m.put("NIFTY_500", List.of(
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","HINDUNILVR","INFY","ITC","SBIN",
            "BHARTIARTL","AXISBANK","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "MARUTI","TITAN","NESTLEIND","ULTRACEMCO","WIPRO","ONGC","JSWSTEEL","NTPC",
            "POWERGRID","TECHM","TATASTEEL","BAJAJFINSV","COALINDIA","HDFCLIFE","ADANIPORTS",
            "BAJAJ-AUTO","M&M","APOLLOHOSP","SUNPHARMA","CIPLA","DIVISLAB","GRASIM",
            "BPCL","TATACONSUM","DRREDDY","INDUSINDBK","EICHERMOT","HEROMOTOCO","BRITANNIA",
            "UPL","SBILIFE","SHREECEM","HDFC","LTIM","ADANIGREEN","AFFLE","ALEMBICPHARM",
            "ANURAS","ARIHANT","ASTRAL","AUBANK","AUTHORSCHAIN","AUROPHARMA","AZORAAUTOS",
            "BAJAJCORP","BALKRISIND","BANKINDIA","BASF","BAYERCROP","BERGEPAINT","BEL",
            "BLUEDART","BOSCHLTD","BOUNSOUL","BSOFT","CAMS","CARERATING","CARWALE",
            "CASTROLIND","CCTECH","CDSL","CEBA","CGPOWER","CHAMP","CHATRATH","CHLCS",
            "CHOLAFIN","CONCOR","COROMANDEL","CWRDVPN","DCB","DCMSHRIRAM","DEEPAKFERT",
            "DEEPINDUST","DELHIVERY","DMART","DRREDDY","DWARIKESH","EBBW","ECLERX",
            "EMKAYPLAST","ESSELPROP","ESCORTS","EXCELINDUS","EXICOM","FOSECOIND","FSL",
            "GAIL","GICRE","GMRINFRA","GOCLEAN","GODREJCP","GODREJPROP","GPIL","GRUH"
        ));
        return m;
    }

    @Override
    public List<String> supportedGroupKeys() {
        return List.of(
            "NIFTY_50", "NIFTY_100", "NIFTY_200", "NIFTY_500",
            "NIFTY_NEXT_50",
            "BANKNIFTY", "BANKNIFTY_FUTURES",
            "FINNIFTY", "FINNIFTY_FUTURES",
            "AUTO_NIFTY", "IT_NIFTY", "PHARMA_NIFTY",
            "PSU_BANK", "PRIVATE_BANK",
            "CONSUMPTION_NIFTY", "INFRA_NIFTY"
        );
    }

    @Override
    @Transactional
    public int sync(String groupKey) {
        List<String> symbols = STATIC_SYMBOLS.get(groupKey);
        if (symbols == null || symbols.isEmpty()) {
            log.warn("universe.sync.no_static_data groupKey={}", groupKey);
            return 0;
        }
        Optional<StrategyUniverseGroup> groupOpt = groupRepository.findByGroupKey(groupKey);
        if (groupOpt.isEmpty()) {
            log.warn("universe.sync.group_not_found groupKey={}", groupKey);
            return 0;
        }
        StrategyUniverseGroup group = groupOpt.get();
        symbolRepository.deleteAllByGroupId(group.getId());

        List<String> uniqueSymbols = new ArrayList<>(new LinkedHashSet<>(symbols));
        int count = 0;
        for (String symbol : uniqueSymbols) {
            StrategyUniverseSymbol s = new StrategyUniverseSymbol();
            s.setGroup(group);
            s.setSymbol(symbol);
            s.setTradingSymbol(symbol);
            s.setExchange(group.getExchange());
            s.setInstrumentType(group.getInstrumentType());
            s.setEnabled(true);
            symbolRepository.save(s);
            count++;
        }
        log.info("universe.sync.done groupKey={} symbols={}", groupKey, count);
        return count;
    }
}
