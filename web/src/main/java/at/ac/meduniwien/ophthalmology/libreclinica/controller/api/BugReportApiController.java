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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E — In-app bug-report adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code POST /pages/api/v1/bug-report} — accepts a structured
 *       bug report (title + description + optional reproduction steps
 *       + page URL + user agent) from any authenticated user,
 *       composes a plain-text email, and dispatches it via
 *       {@link OpenClinicaMailSender} to the institutional inbox
 *       configured under {@code libreclinica.bugReport.recipient}.
 *       Returns {@code 200 + { delivered: true, ticketId: "BUG-<ts>" }}
 *       on success so the operator can quote the ticket id in a
 *       follow-up conversation.</li>
 * </ul>
 *
 * <p><strong>Authorization.</strong> Any authenticated user can file
 * a report. The chain-level {@code .anyRequest().hasRole("USER")} is
 * sufficient; no per-role gating because operators across every role
 * may hit a UI / data issue worth reporting.
 *
 * <p><strong>Audit.</strong> Two distinct rows depending on the
 * outcome:
 * <ul>
 *   <li>Success → one {@code audit_log_event} row of type
 *       {@link #AUDIT_TYPE_BUG_REPORT_FILED} (64), entity_id = user_id,
 *       new_value = ticket id. Surfaced by the SPA's Audit Log view
 *       (is_user_visible=true on the seed row).</li>
 *   <li>Mail-send throws → {@link FailureAuditTemplate} writes one
 *       {@code OPERATION_FAILED} (type 61) row tagged with
 *       audit_table=bug_report, entity_id=null, operation =
 *       {@code SEND_BUG_REPORT}, before the controller responds 500.
 *       Pattern matches {@link OpenClinicaMailSender}'s own
 *       fire-and-forget smtp-failure audit, but the wrapper lets the
 *       controller record the failure with the actual user as the
 *       actor (not the system actor 0) so compliance can trace the
 *       failed dispatch back to the reporter.</li>
 * </ul>
 *
 * <p><strong>Recipient not configured.</strong> When
 * {@code libreclinica.bugReport.recipient} is blank (default safe
 * value in application.yml + the LIBRECLINICA_BUG_REPORT_RECIPIENT
 * env var unset), the endpoint returns {@code 503} with a
 * {@link ValidationErrorBody} pointing the operator at the sysadmin.
 * No audit row is written — the failure is a config issue, not a
 * runtime fault.
 */
@RestController
@RequestMapping("/api/v1/bug-report")
@Tag(name = "Bug Report",
     description = "Operator-filed bug reports — composes email + audit trail.")
public class BugReportApiController {

    private static final Logger LOG = LoggerFactory.getLogger(BugReportApiController.class);

    /** audit_log_event_type_id for successful bug-report submissions (Liquibase seed). */
    private static final int AUDIT_TYPE_BUG_REPORT_FILED = 64;

    /** Cap on title length — matches the validation contract documented in the SPA. */
    private static final int MAX_TITLE_LEN = 200;

    /** Cap on description / reproduction-steps length — generous; mail body grows linearly. */
    private static final int MAX_DESCRIPTION_LEN = 5000;

    private final DataSource dataSource;
    private final OpenClinicaMailSender mailSender;
    private final String recipient;

    @Autowired
    public BugReportApiController(
            @Qualifier("dataSource") DataSource dataSource,
            OpenClinicaMailSender mailSender,
            @Value("${libreclinica.bugReport.recipient:}") String recipient) {
        this.dataSource = dataSource;
        this.mailSender = mailSender;
        this.recipient = recipient == null ? "" : recipient.trim();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = BugReportResponse.class)))
    public ResponseEntity<?> submit(@RequestBody(required = false) BugReportRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        // Validation — fields first so the SPA can light up per-field errors.
        List<FieldError> errors = new ArrayList<>();
        String title = body == null ? null : trimToNull(body.title());
        if (title == null) {
            errors.add(new FieldError("title", "Title is required."));
        } else if (title.length() > MAX_TITLE_LEN) {
            errors.add(new FieldError("title",
                    "Title must be " + MAX_TITLE_LEN + " characters or fewer."));
        }
        String description = body == null ? null : trimToNull(body.description());
        if (description == null) {
            errors.add(new FieldError("description", "Description is required."));
        } else if (description.length() > MAX_DESCRIPTION_LEN) {
            errors.add(new FieldError("description",
                    "Description must be " + MAX_DESCRIPTION_LEN + " characters or fewer."));
        }
        String reproductionSteps = body == null ? null : trimToNull(body.reproductionSteps());
        if (reproductionSteps != null && reproductionSteps.length() > MAX_DESCRIPTION_LEN) {
            errors.add(new FieldError("reproductionSteps",
                    "Reproduction steps must be " + MAX_DESCRIPTION_LEN + " characters or fewer."));
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed.", errors));
        }

        // Recipient gate — config-time concern; treat as 503 so the SPA
        // surfaces a "contact your sysadmin" copy distinct from the
        // user-correctable 400 above.
        if (recipient.isEmpty()) {
            LOG.warn("Bug-report submission blocked: libreclinica.bugReport.recipient is empty");
            return ResponseEntity.status(503).body(new ValidationErrorBody(
                    "Bug-report email recipient is not configured; contact the sysadmin.",
                    List.of()));
        }

        String ticketId = "BUG-" + System.currentTimeMillis();
        String from = resolveFromAddress();
        String subject = "[LibreClinicaMUW Bug Report] " + ticketId + " - " + title;
        StudyBean activeStudy = (StudyBean) session.getAttribute("study");
        String emailBody = composeBody(ticketId, me, activeStudy,
                body.pageUrl(), body.userAgent(),
                title, description, reproductionSteps);

        // Wrap the send so a throw lands an OPERATION_FAILED audit row.
        // FailureAuditTemplate eagerly takes an AuditEventDAO at call
        // site, but constructing the DAO triggers SQLFactory bootstrap
        // — fine in production, NPEs in unit tests with a mock
        // DataSource. Build the DAO lazily inside the catch so the
        // happy path doesn't pay the eager-construction cost (and the
        // unit-test path stays green without a Mockito-static dance).
        try {
            mailSender.sendEmail(recipient, from, subject, emailBody, /*htmlEmail*/ false);
        } catch (RuntimeException sendFailure) {
            LOG.error("Bug-report email dispatch failed for ticket {} from user_id={}",
                    ticketId, me.getId(), sendFailure);
            try {
                FailureAuditTemplate.runOrAudit(
                        new AuditEventDAO(dataSource),
                        me.getId(),
                        "bug_report",
                        null,
                        "SEND_BUG_REPORT",
                        MDC.get("reqId"),
                        () -> { throw sendFailure; });
            } catch (Throwable auditPath) {
                // Either the audit-write itself failed or runOrAudit
                // rethrew the original — both are already logged by
                // the template / above. Swallow here so the operator
                // gets a uniform 500 response.
                LOG.debug("Failure-audit pipeline result: {}", auditPath.getMessage());
            }
            return ResponseEntity.status(500).body(new ValidationErrorBody(
                    "Failed to dispatch bug report — see server log; the failure has been audited.",
                    List.of()));
        }

        // Success-audit. Same direct-INSERT pattern the sibling
        // MeApiController + BuildStudyApiController use; the legacy
        // AuditEventDAO.create() drops the columns we care about.
        emitSuccessAudit(me.getId(), ticketId, title);

        LOG.info("Bug report {} filed by user_id={} -> recipient={}",
                ticketId, me.getId(), recipient);

        return ResponseEntity.ok(new BugReportResponse(true, ticketId));
    }

    /**
     * Plain-text email body. Section order matches the brief so a
     * compose audit on the staging server line-matches institutional
     * triage tooling that scrapes the headers.
     */
    private static String composeBody(String ticketId,
                                      UserAccountBean me,
                                      StudyBean activeStudy,
                                      String pageUrl,
                                      String userAgent,
                                      String title,
                                      String description,
                                      String reproductionSteps) {
        String reportedAt = Instant.now().toString();
        String study = activeStudy != null && activeStudy.getId() > 0
                ? activeStudy.getOid() + " (" + nullToBlank(activeStudy.getName()) + ")"
                : "none";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Ticket: ").append(ticketId).append('\n');
        sb.append("Reporter: ").append(nullToBlank(me.getName()))
                .append(" (").append(me.getId()).append(")\n");
        sb.append("Reported at: ").append(reportedAt).append('\n');
        sb.append("Active study: ").append(study).append('\n');
        sb.append("Page URL: ").append(nullToBlank(pageUrl)).append('\n');
        sb.append("User agent: ").append(nullToBlank(userAgent)).append('\n');
        sb.append('\n');
        sb.append("Title: ").append(title).append('\n');
        sb.append('\n');
        sb.append("Description:\n").append(description).append('\n');
        sb.append('\n');
        sb.append("Reproduction steps:\n");
        sb.append(reproductionSteps == null ? "(none provided)" : reproductionSteps).append('\n');
        return sb.toString();
    }

    private void emitSuccessAudit(int userId, String ticketId, String title) {
        // Catch RuntimeException too: Mockito mock DataSource returns
        // null from getConnection(), which would NPE inside the
        // try-with-resources. The success-audit row is best-effort;
        // never let a missing audit-write topple a delivered mail.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'bug_report', ?, ?, '', ?)")) {
            ps.setInt(1, AUDIT_TYPE_BUG_REPORT_FILED);
            ps.setInt(2, userId);
            // entity_id = user_id keeps the foreign-key-shaped column
            // pointing at the actor (no domain row to reference), so a
            // sysadmin filtering by user_id sees both the actor and
            // the bug-report entries on the same scan.
            ps.setInt(3, userId);
            ps.setString(4, ticketId);
            ps.setString(5, title);
            ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            // Don't propagate — the mail has already been sent. A lost
            // success-audit row is annoying; a 500 after a successful
            // dispatch would make the operator double-send.
            LOG.warn("Failed to write BUG_REPORT_FILED audit for ticket {}: {}",
                    ticketId, e.getMessage());
        }
    }

    /**
     * Best-effort from-address derivation. Pulls
     * {@link EmailEngine#getAdminEmail()} from {@code datainfo.properties}
     * in production; in unit tests the static initialiser is not run so
     * the field-resolver NPEs. The override-friendly method lets the
     * test subclass return a sentinel without booting the full
     * {@code CoreResources} pipeline. Visible for testing.
     */
    protected String resolveFromAddress() {
        try {
            String admin = EmailEngine.getAdminEmail();
            return (admin == null || admin.isBlank()) ? "no-reply@libreclinica.local" : admin;
        } catch (RuntimeException e) {
            // CoreResources.DATAINFO not initialised (unit-test path).
            LOG.debug("resolveFromAddress fallback: {}", e.getMessage());
            return "no-reply@libreclinica.local";
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    /**
     * Request body for {@code POST /pages/api/v1/bug-report}.
     *
     * <p>{@code reproductionSteps}, {@code pageUrl}, and {@code userAgent}
     * are optional. {@code title} and {@code description} are required +
     * length-capped; see the validation block in {@link #submit}.
     */
    public record BugReportRequest(
            String title,
            String description,
            String reproductionSteps,
            String pageUrl,
            String userAgent) {}

    /** Success response — operator quotes {@code ticketId} in follow-up conversations. */
    public record BugReportResponse(boolean delivered, String ticketId) {}
}
