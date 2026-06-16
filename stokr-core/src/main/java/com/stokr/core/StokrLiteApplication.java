package com.stokr.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.stokr.core",
    "com.stokr.trading"
})
public class StokrLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(StokrLiteApplication.class, args);
    }
}
