/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R1.2 — the test e-mail, against a real PostgreSQL with the production
 * changelog applied.
 *
 * <p>The mail goes through the production {@link OpenClinicaMailSender}; only
 * the {@link JavaMailSenderImpl} under it is a spy whose {@code send} records
 * the message or throws, as {@link EmailFailureAuditDatabaseIT} stubs it.
 *
 * <p>Pinned: the mail goes to the caller's own address as stored now, and a
 * recipient in the request changes nothing; callers who are not a system
 * administrator send nothing; a failed send answers with the mail server's
 * error and leaves the failure audit row; a second request inside the
 * interval is refused; an account without an address is told so.
 */
class AdminMailApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String URL = "/api/v1/admin/test-email";

    private static final int ADMIN_ID = 10301;
    private static final String ADMIN_ADDRESS = "it-admin@example.invalid";
    private static final int NO_ADDRESS_ID = 10302;
    private static final String SENDER = "noreply@example.invalid";

    /** The legacy servlet's subject, kept. */
    private static final String SUBJECT = "[LibreClinica] Test Email";

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.execute(user(ADMIN_ID, "it-mail-admin", "'" + ADMIN_ADDRESS + "'"));
            st.execute(user(NO_ADDRESS_ID, "it-mail-noaddress", "''"));
        }
    }

    private static String user(int id, String name, String emailLiteral) {
        return "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                + "email, active_study, institutional_affiliation, status_id, owner_id, "
                + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                + "run_webservices, authtype, enable_api_key) "
                + "VALUES (" + id + ", '" + name + "', "
                + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                + "'Mail', 'Admin', " + emailLiteral + ", 1, 'MUW (test)', 1, 1, "
                + "current_timestamp, 1, true, true, 0, false, 'STANDARD', false)";
    }

    /* ---- wiring ------------------------------------------------------- */

    /** A mail sender whose {@code send} records the message instead of reaching a server. */
    private static JavaMailSenderImpl recording() {
        JavaMailSenderImpl spy = Mockito.spy(new JavaMailSenderImpl());
        Mockito.doAnswer(_ -> new MimeMessage(Session.getInstance(new Properties())))
                .when(spy).createMimeMessage();
        Mockito.doNothing().when(spy).send(any(MimeMessage.class));
        return spy;
    }

    /** A mail sender whose server refuses the connection. */
    private static JavaMailSenderImpl refusing() {
        JavaMailSenderImpl spy = recording();
        Mockito.doThrow(new MailSendException("Mail server connection failed; Connection refused"))
                .when(spy).send(any(MimeMessage.class));
        return spy;
    }

    private static MockMvc mvc(JavaMailSenderImpl transport) {
        OpenClinicaMailSender mailSender = new OpenClinicaMailSender();
        mailSender.setMailSender(transport);
        mailSender.setDataSource(DATA_SOURCE);
        AdminMailApiController controller = new AdminMailApiController(DATA_SOURCE, mailSender) {
            @Override
            protected String resolveAdminEmail() {
                return SENDER;
            }
        };
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin(int id) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(id);
        ub.setName("it-mail-admin");
        // What the session was opened with; the endpoint must read the account.
        ub.setEmail("stale-session-address@example.invalid");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        return s;
    }

    private static MockHttpSession physician() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("physician");
        s.setAttribute("userBean", ub);
        return s;
    }

    private static MimeMessage sentMessage(JavaMailSenderImpl transport) {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(sent.capture());
        return sent.getValue();
    }

    private static String[] addresses(Address[] list) {
        return Arrays.stream(list).map(Address::toString).toArray(String[]::new);
    }

    /* ---- tests -------------------------------------------------------- */

    @Test
    void theTestMailGoesToTheCallersOwnAddressAsStored() throws Exception {
        JavaMailSenderImpl transport = recording();
        mvc(transport).perform(post(URL).session(sysadmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.recipient").value(ADMIN_ADDRESS));

        MimeMessage mail = sentMessage(transport);
        assertArrayEquals(new String[] {ADMIN_ADDRESS}, addresses(mail.getAllRecipients()));
        assertArrayEquals(new String[] {SENDER}, addresses(mail.getFrom()));
        assertEquals(SUBJECT, mail.getSubject());
    }

    @Test
    void aRecipientInTheRequestChangesNothing() throws Exception {
        JavaMailSenderImpl transport = recording();
        mvc(transport).perform(post(URL).session(sysadmin(ADMIN_ID))
                        .param("recipient", "someone-else@example.invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"someone-else@example.invalid\",\"to\":\"x@example.invalid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value(ADMIN_ADDRESS));

        assertArrayEquals(new String[] {ADMIN_ADDRESS}, addresses(sentMessage(transport).getAllRecipients()));
    }

    @Test
    void callersWhoAreNotASystemAdministratorSendNothing() throws Exception {
        JavaMailSenderImpl transport = recording();
        mvc(transport).perform(post(URL).session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
        mvc(transport).perform(post(URL).session(physician()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Sysadmin privilege required."));
        verify(transport, never()).send(any(MimeMessage.class));
    }

    @Test
    void aFailedSendReportsTheMailServersErrorAndIsAudited() throws Exception {
        long before = failureRows();
        JavaMailSenderImpl transport = refusing();
        mvc(transport).perform(post(URL).session(sysadmin(ADMIN_ID)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.recipient").value(ADMIN_ADDRESS))
                .andExpect(jsonPath("$.message", Matchers.containsString("Connection refused")));
        assertEquals(before + 1, failureRows(), "the failed mail leaves its OPERATION_FAILED row");
    }

    @Test
    void aSecondRequestWithinTheIntervalIsRefused() throws Exception {
        JavaMailSenderImpl transport = recording();
        MockMvc mvc = mvc(transport);
        mvc.perform(post(URL).session(sysadmin(ADMIN_ID))).andExpect(status().isOk());
        mvc.perform(post(URL).session(sysadmin(ADMIN_ID)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", Matchers.matchesPattern("[1-9][0-9]*")));
        verify(transport, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void anAccountWithoutAnAddressIsToldAndNothingIsSent() throws Exception {
        JavaMailSenderImpl transport = recording();
        mvc(transport).perform(post(URL).session(sysadmin(NO_ADDRESS_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", Matchers.containsString("e-mail address")));
        verify(transport, never()).send(any(MimeMessage.class));
    }

    private static long failureRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = 61 "
                             + "AND audit_table = 'email' AND entity_name = ?")) {
            ps.setString(1, "EmailEngine.sendEmail." + SUBJECT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
