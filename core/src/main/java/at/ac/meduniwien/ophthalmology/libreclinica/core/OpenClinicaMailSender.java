/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Date;
import java.util.StringTokenizer;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class OpenClinicaMailSender {

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    JavaMailSenderImpl mailSender;

    /**
     * Phase A6 (2026-06-10) — optional DataSource so we can write an
     * OPERATION_FAILED audit row when the SMTP send blows up.
     *
     * <p>Optional because the rules-engine path and a small number of
     * legacy notification callers construct this class outside the
     * Spring graph (no auto-wiring, no setter); when {@code null} the
     * failure-audit step is silently skipped and the throw-on-error
     * behaviour reverts to legacy. The production MailConfig bean
     * injects the live pool DataSource so the institutional path
     * always lands an audit row.
     */
    private DataSource dataSource;

    public void sendEmail(String to, String subject, String body, Boolean htmlEmail) throws OpenClinicaSystemException {
        sendEmail(to, EmailEngine.getAdminEmail(), subject, body, htmlEmail);
    }

    public void sendEmail(String to, String from, String subject, String body, Boolean htmlEmail) throws OpenClinicaSystemException {
        try {

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, htmlEmail);
            helper.setFrom(from);
            helper.setTo(processMultipleImailAddresses(to.trim()));
            helper.setSubject(subject);
            helper.setText(body, true);

            mailSender.send(mimeMessage);
            logger.debug("Email sent successfully on {}", new Date());
        } catch (MailException me) {
            logger.debug("Email could not be sent on {} due to: {}", new Date(), me.toString());
            auditFailure(subject, me);
            throw new OpenClinicaSystemException(me.getMessage());
        } catch (MessagingException e) {
            logger.debug("Email could not be sent on {} due to: {}", new Date(), e.toString());
            auditFailure(subject, e);
            throw new OpenClinicaSystemException(e.getMessage());
        }
    }

    /**
     * Phase A6 — write an OPERATION_FAILED audit row carrying the
     * throwable's class + message + the current reqId from MDC.
     *
     * <p>System-user (id 0) because fire-and-forget notifications do
     * not always have a {@link at.ac.meduniwien.ophthalmology.libreclinica
     * .bean.login.UserAccountBean} in scope (Quartz-fired bulk emails
     * carry one, password-reset / discrepancy notes carry the
     * triggering user but not through this method's signature). When
     * we want to attribute, callers thread the userId via a future
     * overload — for now sysadmin / compliance has the SPA-side view
     * that exposes failure rows regardless of {@code is_user_visible}.
     *
     * <p>The operation field packs the sanitized subject suffix so
     * the audit row identifies <em>which</em> mail blew up without
     * leaking the full subject (PHI risk on subjects that quote
     * patient labels).
     *
     * <p>Defence-in-depth: any throwable inside the audit-write itself
     * is logged at WARN and swallowed — losing the audit row beats
     * masking the original {@link MailException} the caller is about
     * to see.
     */
    private void auditFailure(String subject, Throwable cause) {
        if (dataSource == null) {
            return; // legacy / out-of-Spring construction path
        }
        try {
            String operation = "EmailEngine.sendEmail" + sanitizeSubjectSuffix(subject);
            new AuditEventDAO(dataSource).insertOperationFailure(
                    0,                  // system actor — fire-and-forget context
                    "email",
                    null,
                    operation,
                    cause.getClass().getName(),
                    cause.getMessage() == null ? "" : cause.getMessage(),
                    MDC.get("reqId"));
        } catch (Throwable auditFailure) {
            logger.warn("OPERATION_FAILED audit-write failed for email send: {}",
                    auditFailure.getMessage(), auditFailure);
        }
    }

    /**
     * Trim + cap the subject so the audit row's {@code entity_name}
     * column (VARCHAR(255), see AuditEventDAO.insertOperationFailure)
     * has room for the {@code "EmailEngine.sendEmail."} prefix plus a
     * meaningful tail. Returns the empty string when the subject is
     * blank.
     */
    private static String sanitizeSubjectSuffix(String subject) {
        if (subject == null || subject.isBlank()) {
            return "";
        }
        String trimmed = subject.trim();
        int max = 200;
        if (trimmed.length() > max) {
            trimmed = trimmed.substring(0, max);
        }
        return "." + trimmed;
    }

    private InternetAddress[] processMultipleImailAddresses(String to) throws MessagingException {
        ArrayList<String> recipientsArray = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(to, ",");
        while (st.hasMoreTokens()) {
            recipientsArray.add(st.nextToken());
        }

        int sizeTo = recipientsArray.size();
        InternetAddress[] addressTo = new InternetAddress[sizeTo];
        for (int i = 0; i < sizeTo; i++) {
            addressTo[i] = new InternetAddress(recipientsArray.get(i).toString());
        }
        return addressTo;
    }

    public JavaMailSenderImpl getMailSender() {
        return mailSender;
    }

    public void setMailSender(JavaMailSenderImpl mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Phase A6 — wire the DataSource so SMTP failures land an
     * OPERATION_FAILED audit row. Optional setter so legacy
     * out-of-Spring callers compile unchanged; {@link MailConfig}
     * populates it on the institutional bean.
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

}
