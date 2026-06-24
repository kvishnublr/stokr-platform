package com.stokr.config;

import com.stokr.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                        AntPathRequestMatcher.antMatcher("/api/auth/login"),
                        AntPathRequestMatcher.antMatcher("/api/auth/register"),
                        AntPathRequestMatcher.antMatcher("/api/auth/refresh"),
                        AntPathRequestMatcher.antMatcher("/api/signals/**"),
                        AntPathRequestMatcher.antMatcher("/actuator/**"),
                        AntPathRequestMatcher.antMatcher("/api/brokers/*/callback"),
                        AntPathRequestMatcher.antMatcher("/api/broker/*/callback"),
                        AntPathRequestMatcher.antMatcher("/webhooks/**"),
                        AntPathRequestMatcher.antMatcher("/api/zerodha/**"),
                        AntPathRequestMatcher.antMatcher("/api/admin/candles/**"),
                        AntPathRequestMatcher.antMatcher("/"),
                        AntPathRequestMatcher.antMatcher("/index.html"),
                        AntPathRequestMatcher.antMatcher("/assets/**"),
                        AntPathRequestMatcher.antMatcher("/favicon*"),
                        AntPathRequestMatcher.antMatcher("/*.svg"),
                        AntPathRequestMatcher.antMatcher("/*.js"),
                        AntPathRequestMatcher.antMatcher("/*.css"),
                        AntPathRequestMatcher.antMatcher("/error")
                    ).permitAll();
                    auth.requestMatchers(AntPathRequestMatcher.antMatcher("/api/admin/**")).hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"error\":\"Unauthorized\"}");
                        })
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:5173", "http://localhost:3000",
                "https://stokr.in", "http://stokr.in",
                "https://www.stokr.in", "http://www.stokr.in",
                "http://173.249.55.84:8082", "http://173.249.55.84"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
