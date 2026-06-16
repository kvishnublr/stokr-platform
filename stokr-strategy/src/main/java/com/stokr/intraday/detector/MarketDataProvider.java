package com.stokr.intraday.detector;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class MarketDataProvider {

    public MarketDataProvider() {
    }

    @Data
    @AllArgsConstructor
    public static class IndexMarketData {
        public String indexName;
        public BigDecimal currentPrice;
        public BigDecimal vixLevel;
        public BigDecimal pcrRatio;
        public BigDecimal volume5m;
        public BigDecimal change5m;
        public BigDecimal trend30m;
        public BigDecimal sessionOpen;
        public BigDecimal prevClose;
        public Long timestamp;
        public BigDecimal niftyCallLTP;
        public BigDecimal niftyPutLTP;
        public BigDecimal bnfCallLTP;
        public BigDecimal bnfPutLTP;
        public BigDecimal momentum5m;
        public BigDecimal recent3minHigh;
        public BigDecimal recent3minLow;
        public BigDecimal recent5mHigh;
        public BigDecimal recent5mLow;
        public BigDecimal vwap;
        public BigDecimal sma20;
        public BigDecimal sma50;
        public BigDecimal range5m;

        public IndexMarketData() {
        }
    }

    public IndexMarketData getIndexMarketData(String indexName) {
        return null;
    }

    public List<IndexBarData> get5minHistory(String indexName, int barCount) {
        return new ArrayList<>();
    }

    public BigDecimal getTrend30m(String indexName, BigDecimal prevClose) {
        return BigDecimal.ZERO;
    }

    public BigDecimal getPCRRatio(String indexName) {
        return BigDecimal.ZERO;
    }

    public BigDecimal getVIX() {
        return BigDecimal.ZERO;
    }

    public BigDecimal getSessionOpenPrice(String indexName) {
        return BigDecimal.ZERO;
    }

    public Map<String, BigDecimal> getRecent3minExtremes(String indexName) {
        return new HashMap<>();
    }

    public int getCurrentTimeMinutes() {
        LocalTime ist = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return ist.getHour() * 60 + ist.getMinute();
    }

    public boolean isMarketOpen() {
        int timeMin = getCurrentTimeMinutes();
        return timeMin >= 555 && timeMin < 930;
    }

    @Data
    @AllArgsConstructor
    public static class IndexBarData {
        public Long timestamp;
        public BigDecimal open;
        public BigDecimal high;
        public BigDecimal low;
        public BigDecimal close;
        public Long volume;
    }
}
