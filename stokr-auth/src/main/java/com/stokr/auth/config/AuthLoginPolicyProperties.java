package com.stokr.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stokr.auth.login")
public class AuthLoginPolicyProperties {

    /**
     * Failed password attempts before temporary lockout.
     */
    private int maxFailedAttempts = 5;

    /**
     * Lock duration after too many failures.
     */
    private int lockDurationMinutes = 15;

    /**
     * Password-reset token validity window.
     */
    private int passwordResetHoursValid = 1;

    /**
     * Email verification link validity.
     */
    private int emailVerificationHoursValid = 72;

    public int getMaxFailedAttempts() {
        return maxFailedAttempts <= 0 ? 5 : maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes <= 0 ? 15 : lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }

    public int getPasswordResetHoursValid() {
        return passwordResetHoursValid <= 0 ? 1 : passwordResetHoursValid;
    }

    public void setPasswordResetHoursValid(int passwordResetHoursValid) {
        this.passwordResetHoursValid = passwordResetHoursValid;
    }

    public int getEmailVerificationHoursValid() {
        return emailVerificationHoursValid <= 0 ? 72 : emailVerificationHoursValid;
    }

    public void setEmailVerificationHoursValid(int emailVerificationHoursValid) {
        this.emailVerificationHoursValid = emailVerificationHoursValid;
    }
}
