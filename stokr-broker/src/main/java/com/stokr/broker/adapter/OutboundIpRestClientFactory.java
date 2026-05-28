package com.stokr.broker.adapter;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates RestClient instances bound to specific local (outbound) IPs.
 * Zerodha requires each trader's API calls to originate from their whitelisted IP.
 * When multiple traders share one server with multiple IPs, each trader gets a dedicated IP.
 * Caches clients per IP to avoid re-creating on every API call.
 */
@Component
@Slf4j
public class OutboundIpRestClientFactory {

    private final ConcurrentHashMap<String, RestClient> cache = new ConcurrentHashMap<>();

    /** Default RestClient (no IP binding — uses server's default route). */
    private final RestClient defaultClient = RestClient.builder().build();

    /**
     * Returns a RestClient that binds outbound connections to the given local IP.
     * If outboundIp is null/blank, returns the default (unbound) client.
     */
    public RestClient clientFor(String outboundIp) {
        if (outboundIp == null || outboundIp.isBlank()) {
            return defaultClient;
        }
        String ip = outboundIp.trim();
        return cache.computeIfAbsent(ip, this::buildBoundClient);
    }

    /** Evict a cached client (e.g. when admin changes a trader's IP). */
    public void evict(String outboundIp) {
        if (outboundIp != null && !outboundIp.isBlank()) {
            cache.remove(outboundIp.trim());
        }
    }

    private RestClient buildBoundClient(String ip) {
        log.info("outbound_ip.create_client ip={}", ip);
        try {
            InetAddress localAddr = InetAddress.getByName(ip);

            // Custom route planner forces all connections through the specified local address.
            HttpRoutePlanner routePlanner = new HttpRoutePlanner() {
                @Override
                public HttpRoute determineRoute(HttpHost host, HttpContext context) throws HttpException {
                    boolean secure = "https".equalsIgnoreCase(host.getSchemeName());
                    return new HttpRoute(host, localAddr, secure);
                }
            };

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setRoutePlanner(routePlanner)
                    .build();

            HttpComponentsClientHttpRequestFactory factory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);

            return RestClient.builder()
                    .requestFactory(factory)
                    .build();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid outbound IP: " + ip, e);
        }
    }
}
