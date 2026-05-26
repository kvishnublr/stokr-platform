package com.stokr.user.broker.web;

import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
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
    public RedirectView callback(
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
            return redirect("?zerodha=error");
        }

        if (!hasText(resolvedState) || !hasText(resolvedRequestToken)) {
            log.warn("zerodha.callback.missing_params state={} requestToken={}",
                    hasText(resolvedState), hasText(resolvedRequestToken));
            return redirect("?zerodha=error&reason=missing_params");
        }

        try {
            if (platformMarketFeedService.tryCompleteZerodhaFromOAuthCallback(resolvedState, resolvedRequestToken)) {
                log.info("zerodha.callback.platform_feed.success");
                return redirectAdmin("?platform_feed=ok");
            }
            UUID userId = zerodhaConnectionService.completeOAuth(resolvedState, resolvedRequestToken);
            log.info("zerodha.callback.success userId={}", userId);
            return redirect("?zerodha=ok");
        } catch (Exception e) {
            log.error("zerodha.callback.failed error={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            if (platformMarketFeedService.isPlatformOauthState(resolvedState)) {
                String reason = URLEncoder.encode(e.getClass().getSimpleName(), StandardCharsets.UTF_8);
                return redirectAdmin("?platform_feed=error&reason=" + reason);
            }
            return redirect("?zerodha=error&reason=" + URLEncoder.encode(e.getClass().getSimpleName(), StandardCharsets.UTF_8));
        }
    }

    private RedirectView redirectAdmin(String query) {
        String url = uiPublicBaseUrl.replaceAll("/+$", "") + "/admin/broker-infrastructure" + query;
        return new RedirectView(url);
    }

    private RedirectView redirect(String query) {
        String url = uiPublicBaseUrl.replaceAll("/+$", "") + "/brokers/zerodha-complete" + query;
        return new RedirectView(url);
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
