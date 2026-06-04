package com.stokr.bootstrap.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

public class SecretMaskingConverter extends MessageConverter {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?<=token=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=password=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=secret=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=access_token=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=refresh_token=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=api_key=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=apiKey=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<=\"token\":\")[^\"]+"),
            Pattern.compile("(?<=\"password\":\")[^\"]+"),
            Pattern.compile("(?<=\"secret\":\")[^\"]+"),
            Pattern.compile("(?<=\"apiKey\":\")[^\"]+"),
            Pattern.compile("(?<=\"access_token\":\")[^\"]+"),
            Pattern.compile("(?<=\"refresh_token\":\")[^\"]+"),
            Pattern.compile("(?<=Authorization:\\s*Bearer\\s)[\\w.-]+"),
    };

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        for (Pattern p : PATTERNS) {
            message = p.matcher(message).replaceAll("****");
        }
        return message;
    }
}
