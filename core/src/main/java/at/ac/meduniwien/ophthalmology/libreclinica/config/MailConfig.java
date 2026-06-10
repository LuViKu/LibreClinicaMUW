package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.MailNotificationService;

/**
 * Phase C.7: Java replacement for applicationContext-core-email.xml.
 * <p>
 * Wires the canonical mail bean trio:
 * <ul>
 *   <li>{@code mailSender}              — Spring's {@link JavaMailSenderImpl}, fed from
 *                                          {@code datainfo.properties}-derived keys</li>
 *   <li>{@code openClinicaMailSender}   — LibreClinica's outbound-mail wrapper</li>
 *   <li>{@code mailNotificationService} — OTP notification entry point</li>
 * </ul>
 * Values are resolved by Spring's standard {@code ${...}} placeholder pipeline
 * against the {@code dataInfoProperties} bean (sourced from
 * {@code CoreResources.getDataInfo()}). The custom {@code s[...]} prefix was
 * retired in C.8.
 */
@Configuration
public class MailConfig {

    @Value("${mail.host}")
    private String mailHost;

    @Value("${mail.username}")
    private String mailUsername;

    @Value("${mail.password}")
    private String mailPassword;

    @Value("${mail.port}")
    private int mailPort;

    @Value("${mail.protocol}")
    private String mailProtocol;

    @Value("${mail.smtp.auth}")
    private String mailSmtpAuth;

    @Value("${mail.smtp.starttls.enable}")
    private String mailSmtpStarttlsEnable;

    @Value("${mail.smtps.auth}")
    private String mailSmtpsAuth;

    @Value("${mail.smtps.starttls.enable}")
    private String mailSmtpsStarttlsEnable;

    @Value("${mail.smtp.connectiontimeout}")
    private String mailSmtpConnectionTimeout;

    @Bean
    public JavaMailSenderImpl mailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailHost);
        sender.setUsername(mailUsername);
        sender.setPassword(mailPassword);
        sender.setPort(mailPort);
        sender.setProtocol(mailProtocol);
        sender.setDefaultEncoding("UTF-8");

        Properties props = new Properties();
        props.setProperty("mail.smtp.auth", mailSmtpAuth);
        props.setProperty("mail.smtp.starttls.enable", mailSmtpStarttlsEnable);
        props.setProperty("mail.smtps.auth", mailSmtpsAuth);
        props.setProperty("mail.smtps.starttls.enable", mailSmtpsStarttlsEnable);
        props.setProperty("mail.smtp.connectiontimeout", mailSmtpConnectionTimeout);
        sender.setJavaMailProperties(props);

        return sender;
    }

    @Bean
    public OpenClinicaMailSender openClinicaMailSender(JavaMailSenderImpl mailSender, DataSource dataSource) {
        OpenClinicaMailSender wrapper = new OpenClinicaMailSender();
        wrapper.setMailSender(mailSender);
        // Phase A6 (2026-06-10): wire DataSource so an SMTP failure
        // lands an OPERATION_FAILED audit row before the wrapped
        // OpenClinicaSystemException bubbles to the caller. Without
        // this the audit-write step is silently skipped (legacy
        // fail-open contract preserved for out-of-Spring callers).
        wrapper.setDataSource(dataSource);
        return wrapper;
    }

    @Bean
    public MailNotificationService mailNotificationService(OpenClinicaMailSender openClinicaMailSender) {
        MailNotificationService service = new MailNotificationService();
        service.setMailSender(openClinicaMailSender);
        return service;
    }
}
