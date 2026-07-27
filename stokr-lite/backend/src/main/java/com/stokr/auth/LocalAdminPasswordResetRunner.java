package com.stokr.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalAdminPasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAdminPasswordResetRunner.class);
    private static final String TEMP_ADMIN_PASSWORD = "`$ADMIN_PASSWORD";
    private static final List<String> OWNER_EMAILS = List.of(
            "kvishnu.blr@gmail.com",
            "admin@stokr.in"
    );

    private final AuthRepository authRepository;
    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        OWNER_EMAILS.forEach(email -> {
            var opt = authRepository.findByEmail(email);
            if (opt.isPresent()) {
                authService.resetOwnerPassword(email, TEMP_ADMIN_PASSWORD);
                log.info("Reset local admin password for {}", email);
            } else {
                // create account then promote/reset
                try {
                    authService.register(new AuthService.RegisterRequest(email, TEMP_ADMIN_PASSWORD, "Admin"));
                    authService.resetOwnerPassword(email, TEMP_ADMIN_PASSWORD);
                    log.info("Created and set local admin password for {}", email);
                } catch (Exception e) {
                    log.warn("Failed to create/reset admin {}: {}", email, e.getMessage());
                }
            }
        });
    }
}

