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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * R1.2 (2026-09-30) — send a test e-mail, SPA replacement for the legacy
 * {@code /SendTestEmail}: after a deployment, an administrator checks that
 * the mail configuration works by mailing themselves.
 *
 * <p>{@code POST /api/v1/admin/test-email} sends one fixed message to the
 * caller's own address, as stored on their account now, through the same
 * mail sender as every other mail the application sends
 * ({@link OpenClinicaMailSender}, which wraps the {@code mailSender} bean the
 * legacy servlet used). The request has no body: the endpoint takes no
 * recipient, so it cannot be used to mail anybody else. A failed send answers
 * 502 with the mail server's error, which is the point of the test, and
 * leaves the {@code OPERATION_FAILED} audit row every failed mail leaves.
 *
 * <p>One test mail per administrator per {@link #MIN_INTERVAL}; a request
 * inside it answers 429 with {@code Retry-After}. The button is for checking
 * a configuration, and a mail server's rate limits are easy to trip.
 *
 * <p>System administrators only: anonymous → 401, anyone else → 403, as in
 * {@link AdminApiController}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin tooling",
     description = "Sysadmin-only diagnostic + configuration surfaces — SPA replacement for the legacy admin JSPs.")
public class AdminMailApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminMailApiController.class);

    /** The legacy servlet's subject and text, unchanged. */
    static final String SUBJECT = "[LibreClinica] Test Email";
    static final String BODY =
            "Since you received this email, the email setup of LibreClinica works as expected.";

    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final OpenClinicaMailSender mailSender;

    /** When each administrator last asked for a test mail, by user id. */
    private final Map<Integer, Long> lastRequest = new ConcurrentHashMap<>();

    @Autowired
    public AdminMailApiController(@Qualifier("dataSource") DataSource dataSource,
                                  OpenClinicaMailSender mailSender) {
        this.dataSource = dataSource;
        this.mailSender = mailSender;
    }

    /**
     * The outcome: {@code sent}, the address it went to, and on failure the
     * mail server's message.
     */
    public record TestEmailResult(boolean sent, String recipient, String message) {}

    @PostMapping(value = "/test-email", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sendTestEmail(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message", "Sysadmin privilege required."));
        }

        String recipient;
        try {
            recipient = ownAddress(ub.getId());
        } catch (SQLException e) {
            LOG.error("Could not read the e-mail address of user_id={}: {}", ub.getId(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Could not read your e-mail address — see server log."));
        }
        // The mail sender splits a recipient at commas; one address only.
        if (recipient == null || recipient.isBlank() || recipient.contains(",")) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Your account has no single e-mail address to send the test to. "
                            + "Set one in your profile first."));
        }
        String from = resolveAdminEmail();
        if (from == null || from.isBlank()) {
            return ResponseEntity.status(503).body(Map.of(
                    "message", "No sender address is configured (adminEmail in datainfo.properties)."));
        }

        long waitMillis = reserve(ub.getId(), System.currentTimeMillis());
        if (waitMillis > 0) {
            long seconds = (waitMillis + 999) / 1000;
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                    .body(Map.of("message", "A test e-mail was sent less than "
                            + MIN_INTERVAL.toSeconds() + " seconds ago. Try again in " + seconds + " s."));
        }

        try {
            mailSender.sendEmail(recipient.trim(), from, SUBJECT, BODY, false);
        } catch (RuntimeException failed) {
            LOG.warn("Test e-mail for user_id={} failed: {}", ub.getId(), failed.getMessage());
            return ResponseEntity.status(502).body(new TestEmailResult(false, recipient.trim(),
                    failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()));
        }
        LOG.info("Test e-mail sent for user_id={}", ub.getId());
        return ResponseEntity.ok(new TestEmailResult(true, recipient.trim(), null));
    }

    /**
     * Records this request unless one was made within {@link #MIN_INTERVAL};
     * returns how long to wait, or 0 when the request may go ahead.
     */
    private long reserve(int userId, long now) {
        AtomicLong wait = new AtomicLong();
        lastRequest.compute(userId, (_, previous) -> {
            if (previous != null && now - previous < MIN_INTERVAL.toMillis()) {
                wait.set(MIN_INTERVAL.toMillis() - (now - previous));
                return previous;
            }
            return now;
        });
        return wait.get();
    }

    /** The address on the caller's account now, not the one the session was opened with. */
    private String ownAddress(int userId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT email FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * The sender address, {@code adminEmail} in {@code datainfo.properties}.
     * Overridable because {@code CoreResources} is not loaded in MockMvc runs.
     */
    protected String resolveAdminEmail() {
        try {
            return EmailEngine.getAdminEmail();
        } catch (RuntimeException notLoaded) {
            LOG.debug("adminEmail unavailable: {}", notLoaded.getMessage());
            return null;
        }
    }
}
