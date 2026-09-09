package com.stokr;

import com.stokr.broker.MotilalOswalAdapter;
import com.stokr.broker.BrokerAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class TestRunner implements CommandLineRunner {

    private final MotilalOswalAdapter adapter;

    public TestRunner(MotilalOswalAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== STARTING MOFSL TEST ===");
        try {
            adapter.connectWithTotp(1L, "ars89", "Temp@1234", "4ZRQYJNKVXBZKFJPGPI2JEEXPXMGJ7DJ", "snReRrTZDnkMh0lD", "58c575d5-c4b4-4ae7-80fc-a0b57d7467e3", "ETIPS2100G");
            System.out.println("=== TEST SUCCESS ===");
        } catch (Exception e) {
            System.out.println("=== TEST FAILED: " + e.getMessage() + " ===");
        }
        System.out.println("=== END MOFSL TEST ===");
    }
}
