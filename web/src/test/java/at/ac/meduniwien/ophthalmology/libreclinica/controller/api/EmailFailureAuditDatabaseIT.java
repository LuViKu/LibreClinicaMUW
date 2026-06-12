/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Phase A6 (2026-06-10) — pins the SMTP failure-audit contract.
 *
 * <p>{@link OpenClinicaMailSender#sendEmail} historically caught
 * {@link org.springframework.mail.MailException} (and the broader
 * {@link jakarta.mail.MessagingException}) and rethrew a wrapped
 * {@code OpenClinicaSystemException} with a debug log. That left
 * compliance with no audit signal when an SMTP server was down —
 * the trail diverged from the operator-visible failure mode.
 *
 * <p>This IT pins two contracts:
 * <ol>
 *   <li>{@code emailFailureWritesAuditRow} — a stubbed
 *       {@link JavaMailSenderImpl} that throws {@link MailSendException}
 *       lands exactly one {@code audit_log_event} row with
 *       {@code audit_log_event_type_id=61} (OPERATION_FAILED) and an
 *       operation prefix of {@code "EmailEngine.sendEmail"}.</li>
 *   <li>{@code emailFailureRethrows} — the rethrow path is preserved
 *       (callers receive an {@link OpenClinicaSystemException} so
 *       e.g. the user-creation flow still surfaces the failure).</li>
 * </ol>
 *
 * <p>The "EmailEngine.sendEmail" naming matches the plan label for
 * the conceptual mail-send path; the actual code site is
 * {@link OpenClinicaMailSender#sendEmail}, which is what the
 * production Spring graph wires (see
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.MailConfig}).
 */
class EmailFailureAuditDatabaseIT extends AbstractApiControllerDatabaseIT {

    /**
     * Build an OpenClinicaMailSender wrapped around a Mockito spy of
     * {@link JavaMailSenderImpl}. We override the bean factory's
     * {@code send(MimeMessage)} path to throw {@link MailSendException}
     * unconditionally — that's the path
     * {@link OpenClinicaMailSender#sendEmail} hits after building the
     * MimeMessage helper, so the rest of the method runs unmodified.
     *
     * <p>The createMimeMessage() call is delegated to a real
     * {@link Session} so the MimeMessageHelper construction does not
     * NPE on a mock-returning-null.
     */
    private static OpenClinicaMailSender buildExplodingSender() {
        JavaMailSenderImpl realSender = new JavaMailSenderImpl();
        JavaMailSenderImpl spy = Mockito.spy(realSender);

        // Force createMimeMessage to return a real, instantiable MimeMessage
        // so MimeMessageHelper(...) doesn't NPE.
        Session session = Session.getInstance(new java.util.Properties());
        Mockito.doReturn(new MimeMessage(session)).when(spy).createMimeMessage();

        // Stub the actual send path: throw MailSendException, the
        // exact superclass of MailException OpenClinicaMailSender
        // catches.
        Mockito.doThrow(new MailSendException("simulated SMTP failure"))
                .when(spy).send(Mockito.any(MimeMessage.class));

        OpenClinicaMailSender sender = new OpenClinicaMailSender();
        sender.setMailSender(spy);
        sender.setDataSource(DATA_SOURCE); // A6 audit wiring
        return sender;
    }

    /**
     * Count OPERATION_FAILED rows tagged with the given operation
     * label. Used so each test asserts its own write in isolation
     * without colliding with sibling-test rows.
     */
    private static int countFailureRows(String operationLike) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND audit_table = 'email' "
                             + "AND entity_name LIKE ?")) {
            ps.setString(1, operationLike);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ====================================================================== */
    /* Case 1 — failure lands an OPERATION_FAILED row                          */
    /* ====================================================================== */

    @Test
    void emailFailureWritesAuditRow() throws Exception {
        // Subject carries a marker the row's entity_name column will
        // preserve via the sanitizeSubjectSuffix() path — gives this
        // test its own row scope.
        String marker = "A6-MAIL-" + System.nanoTime();
        String subject = "Test " + marker;

        OpenClinicaMailSender sender = buildExplodingSender();

        try {
            sender.sendEmail(
                    "ops@example.org",
                    "noreply@example.org",
                    subject,
                    "<p>body</p>",
                    Boolean.TRUE);
            Assertions.fail("Expected OpenClinicaSystemException to propagate");
        } catch (OpenClinicaSystemException expected) {
            // OK — the rethrow case is covered by case 2 explicitly;
            // here we only care about the audit-row side effect.
        }

        // Exactly one OPERATION_FAILED row for our subject marker.
        int count = countFailureRows("EmailEngine.sendEmail.%" + marker + "%");
        Assertions.assertEquals(1, count,
                "Expected exactly one OPERATION_FAILED row for marker "
                        + marker + "; got " + count);

        // Row shape: user_id=0 (system), audit_table='email', and the
        // operation begins with the plan-prescribed prefix.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, entity_name, new_value "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 61 "
                             + "AND entity_name LIKE ?")) {
            ps.setString(1, "EmailEngine.sendEmail.%" + marker + "%");
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "Row should exist");
                Assertions.assertEquals(0, rs.getInt("user_id"),
                        "SMTP failure should be tagged as system-user (0)");
                Assertions.assertEquals("email", rs.getString("audit_table"));
                rs.getInt("entity_id");
                Assertions.assertTrue(rs.wasNull(),
                        "entity_id should be SQL NULL — no domain entity at SMTP layer");
                String operation = rs.getString("entity_name");
                Assertions.assertTrue(
                        operation.startsWith("EmailEngine.sendEmail"),
                        "operation should begin with EmailEngine.sendEmail; got: " + operation);
                String newValue = rs.getString("new_value");
                Assertions.assertTrue(
                        newValue.startsWith(MailSendException.class.getName() + "|"),
                        "new_value should start with throwable FQN; got: " + newValue);
            }
        }
    }

    /* ====================================================================== */
    /* Case 2 — audit must not swallow the original throwable                  */
    /* ====================================================================== */

    @Test
    void emailFailureRethrows() {
        // Same setup as case 1; this case explicitly asserts the
        // rethrow path so we're confident a future refactor that
        // accidentally swallows the wrapped OpenClinicaSystemException
        // (e.g. wrapping the catch block in another try without a
        // rethrow) does not silently break upstream callers.
        OpenClinicaMailSender sender = buildExplodingSender();

        OpenClinicaSystemException caught = Assertions.assertThrows(
                OpenClinicaSystemException.class,
                () -> sender.sendEmail(
                        "ops@example.org",
                        "noreply@example.org",
                        "Plain rethrow case " + System.nanoTime(),
                        "<p>body</p>",
                        Boolean.TRUE),
                "OpenClinicaMailSender.sendEmail must rethrow as "
                        + "OpenClinicaSystemException after auditing");

        // Defensive: the rethrown message should not be empty —
        // upstream UI / SPA surfaces this verbatim.
        Assertions.assertNotNull(caught.getMessage(),
                "OpenClinicaSystemException must carry a non-null message");
        Assertions.assertFalse(caught.getMessage().isBlank(),
                "OpenClinicaSystemException message must not be blank");
    }
}
