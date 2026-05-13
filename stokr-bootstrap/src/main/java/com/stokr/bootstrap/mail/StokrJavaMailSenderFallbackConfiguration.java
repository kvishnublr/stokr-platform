package com.stokr.bootstrap.mail;

import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.util.StringUtils;

/**
 * When mail host is supplied only via {@code SPRING_MAIL_HOST} (repo-root {@code .env} literal keys),
 * {@link org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration} may not create a
 * {@link JavaMailSender}. This fallback builds one from the same environment keys used in production.
 */
@Configuration
@Conditional(StokrJavaMailSenderFallbackConfiguration.MailHostPresentCondition.class)
public class StokrJavaMailSenderFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender stokrJavaMailSender(Environment env) {
        String host =
                firstNonBlank(env.getProperty("spring.mail.host"), env.getProperty("SPRING_MAIL_HOST"));
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(parsePort(env));
        sender.setUsername(firstNonBlank(env.getProperty("spring.mail.username"), env.getProperty("SPRING_MAIL_USERNAME")));
        sender.setPassword(env.getProperty("spring.mail.password", env.getProperty("SPRING_MAIL_PASSWORD", "")));

        boolean auth = env.getProperty("SPRING_MAIL_SMTP_AUTH", Boolean.class, true);
        boolean startTls = env.getProperty("SPRING_MAIL_SMTP_STARTTLS", Boolean.class, true);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(auth));
        props.put("mail.smtp.starttls.enable", Boolean.toString(startTls));
        return sender;
    }

    private static int parsePort(Environment env) {
        String p = firstNonBlank(env.getProperty("spring.mail.port"), env.getProperty("SPRING_MAIL_PORT"));
        if (!StringUtils.hasText(p)) {
            return 587;
        }
        try {
            return Integer.parseInt(p.trim());
        } catch (NumberFormatException ex) {
            return 587;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return "";
    }

    static final class MailHostPresentCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean host =
                    StringUtils.hasText(env.getProperty("spring.mail.host"))
                            || StringUtils.hasText(env.getProperty("SPRING_MAIL_HOST"));
            if (!host) {
                return ConditionOutcome.noMatch(ConditionMessage.of("no spring.mail.host or SPRING_MAIL_HOST"));
            }
            return ConditionOutcome.match(ConditionMessage.of("mail host configured"));
        }
    }
}
