package com.stokr.bootstrap.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class AdminDashboardController {

    // ========== ELITE DASHBOARD (NEW - WORLD-CLASS DESIGN WITH PREMIUM ANIMATIONS) ==========
    @GetMapping("/admin/elite")
    public String adminElite() {
        log.info("Admin Elite Dashboard accessed");
        return "forward:/admin-elite-dashboard.html";
    }

    // ========== PREMIUM ULTRA DASHBOARD (NEW - LIGHT ANIMATED WITH ALL FEATURES) ==========
    @GetMapping("/admin/premium-ultra")
    public String adminPremiumUltra() {
        log.info("Admin Premium Ultra Dashboard accessed");
        return "forward:/admin-premium-ultra.html";
    }

    // ========== ULTRA DASHBOARD (NEW - DARK ENTERPRISE WITH ALL FEATURES) ==========
    @GetMapping("/admin/ultra")
    public String adminUltra() {
        log.info("Admin Ultra Dashboard accessed");
        return "forward:/admin-ultra-dashboard.html";
    }

    // ========== MODERN DASHBOARD (NEW - LIGHT CLEAN THEME) ==========
    @GetMapping("/admin/modern")
    public String adminModern() {
        log.info("Admin Modern Dashboard accessed");
        return "forward:/admin-modern-dashboard.html";
    }

    // ========== PREMIUM DASHBOARDS (NEW - LIGHT THEME) ==========
    @GetMapping("/admin/premium")
    public String adminPremium() {
        log.info("Admin Premium Dashboard accessed");
        return "forward:/admin-premium-dashboard.html";
    }

    @GetMapping("/trader/premium")
    public String traderPremium() {
        log.info("Trader Premium Dashboard accessed");
        return "forward:/trader-premium-dashboard.html";
    }

    // ========== COMPREHENSIVE DASHBOARDS ==========
    @GetMapping("/admin/comprehensive")
    public String adminComprehensive() {
        log.info("Admin Comprehensive Dashboard accessed");
        return "forward:/admin-dashboard-comprehensive.html";
    }

    @GetMapping("/trader/comprehensive")
    public String traderComprehensive() {
        log.info("Trader Comprehensive Dashboard accessed");
        return "forward:/trader-dashboard-comprehensive.html";
    }

    // ========== ADVANCED DASHBOARDS ==========
    @GetMapping("/trader/advanced")
    public String advancedTrader() {
        log.info("Advanced Trader Dashboard accessed");
        return "forward:/advanced-trader-dashboard.html";
    }

    @GetMapping("/trader/advanced-enhanced")
    public String advancedEnhanced() {
        log.info("Advanced Enhanced Dashboard accessed");
        return "forward:/advanced-enhanced-dashboard.html";
    }

    // ========== LEGACY DASHBOARDS ==========
    @GetMapping("/admin/dashboard")
    public String dashboard() {
        log.info("Admin dashboard accessed");
        return "forward:/admin-dashboard.html";
    }

    @GetMapping("/admin/dashboard-v2")
    public String dashboardV2() {
        log.info("Admin dashboard V2 accessed");
        return "forward:/admin-dashboard-v2.html";
    }

    // ========== DEFAULT ROUTES ==========
    @GetMapping("/")
    public String redirectToDashboard() {
        return "redirect:/admin/premium";
    }
}
