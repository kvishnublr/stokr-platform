package com.stokr.auth.mail;

/**
 * Outcome of attempting to deliver a verification email (registration / resend).
 */
public enum VerificationEmailSendOutcome {
    /** {@code spring.mail.host} is blank — no SMTP; verify URL is logged at WARN for local dev. */
    NOT_CONFIGURED,
    /** Message accepted by {@link org.springframework.mail.javamail.JavaMailSender}. */
    SENT,
    /** SMTP misconfiguration, network, auth, or transport failure — token remains valid until expiry. */
    SEND_FAILED
}
