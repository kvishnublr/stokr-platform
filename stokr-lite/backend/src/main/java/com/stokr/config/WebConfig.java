package com.stokr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward SPA routes to index.html (React Router handles client-side routing)
        registry.addViewController("/{spring:\\w+}").setViewName("forward:/");
        registry.addViewController("/{spring:\\w+}/{x:\\w+}").setViewName("forward:/");
        registry.addViewController("/{spring:\\w+}/{x:\\w+}/{y:\\w+}").setViewName("forward:/");
    }
}
