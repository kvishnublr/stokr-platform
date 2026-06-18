package com.stokr.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    @ConditionalOnProperty(name = "stokr.dev-mode", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/api/auth/register"),
                                new AntPathRequestMatcher("/api/auth/login"),
                                new AntPathRequestMatcher("/api/auth/refresh"),
                                new AntPathRequestMatcher("/api/auth/forgot-password"),
                                new AntPathRequestMatcher("/api/auth/reset-password"),
                                new AntPathRequestMatcher("/api/auth/verify-email"),
                                new AntPathRequestMatcher("/api/health"),
                                new AntPathRequestMatcher("/api/v5/health"),
                                new AntPathRequestMatcher("/api/integrations/telegram/webhook"),
                                new AntPathRequestMatcher("/api/chartink/**"),
                                new AntPathRequestMatcher("/api/broker/zerodha/callback"),
                                new AntPathRequestMatcher("/api/system/health/fix-all"),
                                new AntPathRequestMatcher("/api/system/health/cleanup-ghost-symbols"),
                                new AntPathRequestMatcher("/api/emergency/force-close-all-positions"),
                                new AntPathRequestMatcher("/api/admin/**"),
                                new AntPathRequestMatcher("/admin/**"),
                                new AntPathRequestMatcher("/actuator/health"),
                                new AntPathRequestMatcher("/actuator/info"),
                                new AntPathRequestMatcher("/v3/api-docs/**"),
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/swagger-ui.html"),
                                new AntPathRequestMatcher("/ws/**")
                        ).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/strategies/catalog", "GET")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/strategies/catalog/signal-stats", "GET")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/adv-dashboard/dashboard-metrics", "GET")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/", "GET")).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web
                .ignoring()
                .requestMatchers(
                        new AntPathRequestMatcher("/stokr-aurora-dashboard.html"),
                        new AntPathRequestMatcher("/stokr-aurora-pro-dashboard.html"),
                        new AntPathRequestMatcher("/static/**"),
                        new AntPathRequestMatcher("/assets/**"),
                        new AntPathRequestMatcher("/css/**"),
                        new AntPathRequestMatcher("/js/**"),
                        new AntPathRequestMatcher("/images/**")
                );
    }
}
