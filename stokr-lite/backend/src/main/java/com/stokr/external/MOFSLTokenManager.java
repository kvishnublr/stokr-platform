package com.stokr.external;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MOFSLTokenManager {

    private static final String URL = "https://openapi.motilaloswal.com/getscripmastercsv?name=NSEFO";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ConcurrentHashMap<String, String> symbolToToken = new ConcurrentHashMap<>();
    
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    @PostConstruct
    public void init() {
        refresh();
    }
    
    @Scheduled(cron = "0 0 8 * * *")
    public void scheduledRefresh() {
        refresh();
    }

    public void refresh() {
        try {
            log.info("Downloading MOFSL NSEFO scrip master...");
            ResponseEntity<String> resp = restTemplate.getForEntity(URL, String.class);
            if (resp.getBody() == null) return;
            
            ConcurrentHashMap<String, String> newMap = new ConcurrentHashMap<>();
            String[] lines = resp.getBody().split("\r?\n");
            
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                String[] cols = line.split(",");
                if (cols.length < 4) continue;
                
                String scripcode = cols[2].trim();
                String scripname = cols[3].trim();
                
                // e.g. BANKNIFTY 30-Mar-2027 CE 81000
                String[] parts = scripname.split(" ");
                if (parts.length >= 4) {
                    try {
                        String underlying = parts[0].replace(" ", "");
                        LocalDate date = LocalDate.parse(parts[1], formatter);
                        String optType = parts[2];
                        int strike = (int) Double.parseDouble(parts[3]);
                        
                        List<String> candidates = buildNfoSymbolCandidates(underlying, date, strike, optType);
                        for (String c : candidates) {
                            newMap.put(c, scripcode);
                        }
                    } catch (Exception e) {
                        // ignore unparseable row
                    }
                } else if (parts.length >= 3 && parts[2].equals("FUT")) {
                    try {
                        String underlying = parts[0].replace(" ", "");
                        LocalDate date = LocalDate.parse(parts[1], formatter);
                        String futSymbol = buildNfoFutSymbol(underlying, date);
                        newMap.put(futSymbol, scripcode);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            symbolToToken.clear();
            symbolToToken.putAll(newMap);
            log.info("Loaded {} NFO instrument mappings for Motilal Oswal", symbolToToken.size());
        } catch (Exception e) {
            log.error("Failed to load MOFSL scrip master: {}", e.getMessage());
        }
    }
    
    public String getToken(String symbol) {
        return symbolToToken.get(symbol);
    }
    
    private List<String> buildNfoSymbolCandidates(String underlying, LocalDate expiryDate, int strike, String type) {
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3).toUpperCase();
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();
        String mCode = (month == 10) ? "O" : (month == 11) ? "N" : (month == 12) ? "D" : String.valueOf(month);

        return List.of(
            String.format("%s%02d%s%d%s", underlying, yy, mon, strike, type),
            String.format("%s%02d%s%02d%d%s", underlying, yy, mCode, day, strike, type),
            String.format("%s%02d%d%02d%d%s", underlying, yy, month, day, strike, type)
        );
    }
    
    private String buildNfoFutSymbol(String underlying, LocalDate expiryDate) {
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3).toUpperCase();
        return String.format("%s%02d%sFUT", underlying, yy, mon);
    }
}
