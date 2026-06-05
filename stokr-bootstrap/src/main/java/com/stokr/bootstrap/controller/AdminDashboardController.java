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

    @GetMapping("/")
    public String redirectToDashboard() {
        return "redirect:/admin/dashboard";
    }
}
