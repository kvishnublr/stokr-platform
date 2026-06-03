package com.stokr.bootstrap.recovery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures recent WARN/ERROR lines from {@code com.stokr} for recovery context.
 */
@Component
public class OperationalRecentLogBuffer {

    private static final int MAX_LINES = 50;

    private final Deque<String> lines = new ConcurrentLinkedDeque<>();

    @PostConstruct
    void attachAppender() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return;
        }
        RingAppender appender = new RingAppender();
        appender.setContext(ctx);
        appender.setName("OperationalRecentLogBuffer");
        appender.start();
        Logger root = ctx.getLogger("com.stokr");
        root.addAppender(appender);
    }

    public List<String> recentLines() {
        return new ArrayList<>(lines);
    }

    private final class RingAppender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
                return;
            }
            String line = event.getTimeStamp() + " " + event.getLevel() + " "
                    + event.getLoggerName() + " - " + event.getFormattedMessage();
            lines.addLast(line);
            while (lines.size() > MAX_LINES) {
                lines.pollFirst();
            }
        }
    }
}
