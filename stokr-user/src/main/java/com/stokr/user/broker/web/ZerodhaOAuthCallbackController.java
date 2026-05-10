package com.stokr.user.broker.web;

import com.stokr.user.broker.ZerodhaConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequestMapping("/api/broker/zerodha")
@RequiredArgsConstructor
public class ZerodhaOAuthCallbackController {

    private final ZerodhaConnectionService zerodhaConnectionService;

    @Value("${stokr.ui.public-base-url:http://localhost:5173}")
    private String uiPublicBaseUrl;

    @GetMapping("/callback")
    public RedirectView callback(
            @RequestParam(required = false) String state,
            @RequestParam(name = "request_token", required = false) String requestToken,
            @RequestParam(required = false) String status
    ) {
        if ("error".equalsIgnoreCase(status != null ? status : "")) {
            return new RedirectView(uiPublicBaseUrl + "/brokers?zerodha=error");
        }
        try {
            zerodhaConnectionService.completeOAuth(state, requestToken);
            return new RedirectView(uiPublicBaseUrl + "/brokers?zerodha=ok");
        } catch (Exception e) {
            return new RedirectView(uiPublicBaseUrl + "/brokers?zerodha=error");
        }
    }
}
