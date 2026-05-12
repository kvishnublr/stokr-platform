package com.stokr.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;

/**
 * Sends HTML verification email when {@link JavaMailSender} is available (SMTP configured).
 */
@Service
public class VerificationEmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailDeliveryService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    public VerificationEmailDeliveryService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public VerificationEmailSendOutcome sendVerificationEmail(String toEmail, String verifyUrl, int hoursValid) {
        if (!StringUtils.hasText(mailHost)) {
            return VerificationEmailSendOutcome.NOT_CONFIGURED;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("spring.mail.host is set but JavaMailSender bean is missing; cannot send verification email");
            return VerificationEmailSendOutcome.SEND_FAILED;
        }
        if (!StringUtils.hasText(mailUsername)) {
            log.warn("spring.mail.username is blank; cannot set From address for verification email");
            return VerificationEmailSendOutcome.SEND_FAILED;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailUsername);
            helper.setTo(toEmail);
            helper.setSubject("Verify your email — Stokr");
            helper.setText(buildHtml(verifyUrl, hoursValid), true);
            sender.send(message);
            log.info("Verification email queued/sent for recipient domain {}", maskEmail(toEmail));
            return VerificationEmailSendOutcome.SENT;
        } catch (MailException ex) {
            log.error("SMTP failure sending verification email (recipient domain {}). {}", maskEmail(toEmail), ex.toString());
            return VerificationEmailSendOutcome.SEND_FAILED;
        } catch (Exception ex) {
            log.error("Unexpected error sending verification email (recipient domain {}). {}", maskEmail(toEmail), ex.toString());
            return VerificationEmailSendOutcome.SEND_FAILED;
        }
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "(redacted)";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String prefix = local.length() <= 2 ? local.charAt(0) + "…" : local.substring(0, 2) + "…";
        return prefix + "@" + domain;
    }

    private static String buildHtml(String verifyUrl, int hoursValid) {
        String safeUrl = verifyUrl == null ? "" : verifyUrl.replace("&", "&amp;");
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Verify email</title></head>
                <body style="margin:0;padding:0;background:#0a0a0a;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#e5e5e5;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#0a0a0a;padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" style="max-width:520px;background:#171717;border:1px solid #262626;border-radius:16px;padding:28px 24px;">
                          <tr><td style="font-size:20px;font-weight:600;color:#fafafa;">Stokr</td></tr>
                          <tr><td style="padding-top:8px;font-size:14px;color:#a3a3a3;">Confirm your email to continue onboarding.</td></tr>
                          <tr><td style="padding-top:28px;" align="center">
                            <a href="%s" style="display:inline-block;background:#fafafa;color:#0a0a0a;text-decoration:none;font-weight:600;font-size:14px;padding:12px 24px;border-radius:10px;">Verify email</a>
                          </td></tr>
                          <tr><td style="padding-top:24px;font-size:13px;color:#737373;line-height:1.5;">
                            This link expires in about <strong style="color:#d4d4d4;">%d hours</strong>. If the button does not work, copy and paste this URL into your browser:<br/>
                            <span style="word-break:break-all;color:#a3a3a3;">%s</span>
                          </td></tr>
                          <tr><td style="padding-top:20px;font-size:12px;color:#525252;">
                            If you did not create a Stokr account, you can ignore this message. For help, contact your workspace administrator or Stokr support.
                          </td></tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(safeUrl, hoursValid, safeUrl);
    }
}
