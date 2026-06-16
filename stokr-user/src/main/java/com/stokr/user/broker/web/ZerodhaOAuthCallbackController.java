package com.stokr.user.broker.web;

import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.HtmlUtils;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Controller
@RequestMapping("/api/broker/zerodha")
@RequiredArgsConstructor
@Slf4j
public class ZerodhaOAuthCallbackController {

    private final ZerodhaConnectionService zerodhaConnectionService;
    private final PlatformMarketFeedService platformMarketFeedService;

    @Value("${stokr.ui.public-base-url:http://localhost:5173}")
    private String uiPublicBaseUrl;

    @GetMapping("/callback")
    public Object callback(
            @RequestParam(required = false) String state,
            @RequestParam(name = "request_token", required = false) String requestToken,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        String resolvedState = firstPresent(state, request.getParameter("State"));
        String resolvedRequestToken = firstPresent(
                requestToken,
                request.getParameter("requestToken"),
                request.getParameter("request-token")
        );
        log.info("zerodha.callback.received status={} hasState={} hasRequestToken={}",
                status, hasText(resolvedState), hasText(resolvedRequestToken));

        if ("error".equalsIgnoreCase(status != null ? status : "")) {
            log.warn("zerodha.callback.user_rejected");
            if (platformMarketFeedService.isPlatformOauthState(resolvedState)) {
                return platformFeedHtml(false, "Zerodha login was cancelled.");
            }
            return redirect(request, "?zerodha=error");
        }

        if (!hasText(resolvedState) || !hasText(resolvedRequestToken)) {
            log.warn("zerodha.callback.missing_params state={} requestToken={}",
                    hasText(resolvedState), hasText(resolvedRequestToken));
            if (platformMarketFeedService.isPlatformOauthState(resolvedState)) {
                return platformFeedHtml(false, "Missing OAuth parameters. Please try again from Telegram.");
            }
            return redirect(request, "?zerodha=error&reason=missing_params");
        }

        try {
            if (platformMarketFeedService.tryCompleteZerodhaFromOAuthCallback(resolvedState, resolvedRequestToken)) {
                log.info("zerodha.callback.platform_feed.success");
                return platformFeedHtml(true, null);
            }
            UUID userId = zerodhaConnectionService.completeOAuth(resolvedState, resolvedRequestToken);
            log.info("zerodha.callback.success userId={}", userId);
            return redirect(request, "?zerodha=ok");
        } catch (Exception e) {
            log.error("zerodha.callback.failed error={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            if (platformMarketFeedService.isPlatformOauthState(resolvedState)) {
                String reasonText = e.getMessage() != null && !e.getMessage().isBlank()
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                return platformFeedHtml(false, reasonText);
            }
            String reasonText = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            return redirect(request, "?zerodha=error&reason=" + URLEncoder.encode(reasonText, StandardCharsets.UTF_8));
        }
    }

    private ResponseEntity<String> platformFeedHtml(boolean success, String reason) {
        String title = success ? "Zerodha connected" : "Zerodha connection failed";
        String message = success
                ? "Zerodha connected — you can close this tab and return to Telegram."
                : (reason != null && !reason.isBlank()
                ? reason
                : "Could not complete Zerodha login. Close this tab and try again from Telegram.");
        String accent = success ? "#34d399" : "#fb7185";
        String icon = success ? "&#10003;" : "&#10007;";
        String safeTitle = HtmlUtils.htmlEscape(title);
        String safeMessage = HtmlUtils.htmlEscape(message);
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                      background: #070b12;
                      color: #e2e8f0;
                      text-align: center;
                    }
                    .card { max-width: 420px; }
                    .icon {
                      font-size: 48px;
                      line-height: 1;
                      color: %s;
                      margin-bottom: 16px;
                    }
                    h1 {
                      margin: 0 0 12px;
                      font-size: 18px;
                      font-weight: 600;
                      color: %s;
                    }
                    p {
                      margin: 0;
                      font-size: 14px;
                      line-height: 1.5;
                      color: #94a3b8;
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="icon">%s</div>
                    <h1>%s</h1>
                    <p>%s</p>
                  </div>
                </body>
                </html>
                """.formatted(safeTitle, accent, accent, icon, safeTitle, safeMessage);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private RedirectView redirect(HttpServletRequest request, String query) {
        String url = resolveUiPublicBaseUrl(request).replaceAll("/+$", "") + "/brokers/zerodha-complete" + query;
        return new RedirectView(url);
    }

    private String resolveUiPublicBaseUrl(HttpServletRequest request) {
        String configured = uiPublicBaseUrl != null ? uiPublicBaseUrl.strip() : "";
        if (hasText(configured) && !isLocalhostBaseUrl(configured)) {
            return configured;
        }
        String forwardedProto = firstPresent(
                request.getHeader("X-Forwarded-Proto"),
                request.getHeader("X-Forwarded-Protocol")
        );
        String host = firstPresent(
                request.getHeader("X-Forwarded-Host"),
                request.getHeader("Host"),
                request.getServerName()
        );
        if (hasText(host) && !isLocalhostHost(host)) {
            String scheme = hasText(forwardedProto) ? forwardedProto.split(",")[0].strip() : "https";
            String forwardedHost = host.split(",")[0].strip();
            return scheme + "://" + forwardedHost;
        }
        return hasText(configured) ? configured : "http://localhost:5173";
    }

    private static boolean isLocalhostBaseUrl(String raw) {
        try {
            java.net.URI uri = java.net.URI.create(raw);
            return isLocalhostHost(uri.getHost());
        } catch (Exception ignored) {
            return raw.contains("localhost") || raw.contains("127.0.0.1");
        }
    }

    private static boolean isLocalhostHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.strip().toLowerCase();
        if ("::1".equals(normalized) || "[::1]".equals(normalized)) {
            return true;
        }
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(0, colon);
        }
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private static String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
