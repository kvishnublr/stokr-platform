package com.stokr.strategy.meanreversion.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
public class MeanReversionSessionResolver {

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime yamlStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime yamlEnd;

    public SessionWindow resolve(SessionFilterMode mode) {
        LocalTime start = yamlStart;
        LocalTime end = yamlEnd;
        return switch (mode) {
            case ALL, INTRADAY_SESSION -> new SessionWindow(start, end);
            case RTH_ONLY -> {
                LocalTime s = start.plusMinutes(15);
                LocalTime e = end.minusMinutes(15);
                if (!e.isAfter(s)) {
                    yield new SessionWindow(start, end);
                }
                yield new SessionWindow(s, e);
            }
        };
    }

    public record SessionWindow(LocalTime start, LocalTime end) {
    }
}
