package com.stokr.bootstrap.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class AdminDashboardController {

    @GetMapping("/admin/dashboard")
    public String dashboard() {
        log.info("Admin dashboard accessed");
        return "forward:/admin-dashboard.html";
    }

    @GetMapping("/admin/dashboard-v2")
    public String dashboardV2() {
        log.info("Admin dashboard V2 (new) accessed");
        return "forward:/admin-dashboard-v2.html";
    }

    @GetMapping("/")
    public String redirectToDashboard() {
        return "redirect:/admin/dashboard-v2";
    }
}
