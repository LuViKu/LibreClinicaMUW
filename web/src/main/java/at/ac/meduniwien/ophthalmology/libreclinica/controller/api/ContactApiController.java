/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Phase E.8 legacy-retirement Slice L2 (2026-06-20) — SPA replacement
 * for the legacy {@code /pages/Contact} JSP form. Unauthenticated
 * users with a question for the institutional admin POST here; the
 * controller composes a plain-text email and dispatches it via the
 * shared {@link OpenClinicaMailSender}.
 *
 * <p><strong>Authorization.</strong> {@code permitAll} on the
 * {@code /pages/api/v1/contact} path. Same audience as the legacy JSP
 * (anyone visiting the login screen can file a question).
 *
 * <p><strong>Rate-limit.</strong> Not gated at the application layer;
 * the institutional reverse proxy is the first line of defence per
 * the same convention as the existing public OCT-upload portal. If
 * abuse becomes visible, add the public form to
 * {@code PublicOctUploadRateLimitFilter}'s guarded prefixes.
 *
 * <p><strong>Recipient.</strong> Pulled from
 * {@link EmailEngine#getAdminEmail()} (the same source the legacy JSP
 * used). When the resolved address is empty / the static initialiser
 * never ran, returns 503 with a "contact your sysadmin" copy — the
 * mail layer would silently swallow the send otherwise.
 *
 * <p><strong>Audit.</strong> Deliberately none. The contact form is
 * a public-facing unauthenticated touchpoint; an audit row keyed by
 * a sender-supplied email is trivially forgeable + would pollute the
 * institutional audit log. The mail-sender's own SMTP log is the
 * authoritative dispatch record.
 */
@RestController
@RequestMapping("/api/v1/contact")
@Tag(name = "Contact",
     description = "Unauthenticated contact form — composes an email to the institutional admin.")
public class ContactApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ContactApiController.class);

    /** Cap on the operator-supplied "name" field. */
    private static final int MAX_NAME_LEN = 200;

    /** Cap on the subject — keeps mail-server subject-line policy happy. */
    private static final int MAX_SUBJECT_LEN = 200;

    /** Cap on the message body. Generous; the mail body grows linearly. */
    private static final int MAX_MESSAGE_LEN = 5000;

    /**
     * Minimal email-shape sanity check. Not RFC-822 perfect — that
     * lives in the actual mail server's address verification. This
     * just rejects "obviously broken" inputs so the SPA can surface a
     * field-level error before the wire roundtrip.
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final OpenClinicaMailSender mailSender;

    @Autowired
    public ContactApiController(OpenClinicaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ContactResponse.class)))
    public ResponseEntity<?> submit(@RequestBody(required = false) ContactRequest body) {
        // Field-level validation — SPA lights up per-field errors.
        List<FieldError> errors = new ArrayList<>();

        String name = body == null ? null : trimToNull(body.name());
        if (name == null) {
            errors.add(new FieldError("name", "Name is required."));
        } else if (name.length() > MAX_NAME_LEN) {
            errors.add(new FieldError("name",
                    "Name must be " + MAX_NAME_LEN + " characters or fewer."));
        }

        String email = body == null ? null : trimToNull(body.email());
        if (email == null) {
            errors.add(new FieldError("email", "Email is required."));
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            errors.add(new FieldError("email", "Enter a valid email address."));
        }

        String subject = body == null ? null : trimToNull(body.subject());
        if (subject == null) {
            errors.add(new FieldError("subject", "Subject is required."));
        } else if (subject.length() > MAX_SUBJECT_LEN) {
            errors.add(new FieldError("subject",
                    "Subject must be " + MAX_SUBJECT_LEN + " characters or fewer."));
        }

        String message = body == null ? null : trimToNull(body.message());
        if (message == null) {
            errors.add(new FieldError("message", "Message is required."));
        } else if (message.length() > MAX_MESSAGE_LEN) {
            errors.add(new FieldError("message",
                    "Message must be " + MAX_MESSAGE_LEN + " characters or fewer."));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed.", errors));
        }

        String recipient = resolveAdminEmail();
        if (recipient == null || recipient.isBlank()) {
            LOG.warn("Contact submission blocked: EmailEngine.getAdminEmail() is empty");
            return ResponseEntity.status(503).body(Map.of(
                    "message",
                    "Contact form is not currently accepting messages; please contact the sysadmin directly."));
        }

        String mailSubject = "[LibreClinicaMUW Contact] " + subject;
        String mailBody = composeBody(name, email, subject, message);

        try {
            // From-address: the sender's email so the institutional
            // inbox can reply directly. Sender-supplied addresses on
            // unauthenticated forms can be spoofed; the reverse proxy
            // is the perimeter — same posture as the public OCT-upload
            // portal.
            mailSender.sendEmail(recipient, email, mailSubject, mailBody, /*htmlEmail*/ false);
        } catch (RuntimeException sendFailure) {
            LOG.error("Contact email dispatch failed (from={} subject={})",
                    email, subject, sendFailure);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to dispatch contact email — please try again later."));
        }

        LOG.info("Contact message dispatched (from={} subject={}) -> {}",
                email, subject, recipient);

        return ResponseEntity.ok(new ContactResponse(true));
    }

    /** Plain-text email body. */
    private static String composeBody(String name, String email, String subject, String message) {
        StringBuilder sb = new StringBuilder(message.length() + 256);
        sb.append("Contact form submission from the LibreClinicaMUW SPA.\n\n");
        sb.append("From:    ").append(name).append('\n');
        sb.append("Email:   ").append(email).append('\n');
        sb.append("Subject: ").append(subject).append('\n');
        sb.append("\nMessage:\n");
        sb.append(message);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Best-effort admin-email resolve. Pulls
     * {@link EmailEngine#getAdminEmail()} in production; in unit tests
     * the static initialiser is not run so the field-resolver NPEs.
     * The override-friendly method lets a test subclass return a
     * sentinel without booting CoreResources.
     */
    protected String resolveAdminEmail() {
        try {
            return EmailEngine.getAdminEmail();
        } catch (RuntimeException e) {
            LOG.debug("resolveAdminEmail fallback: {}", e.getMessage());
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Request body for {@code POST /pages/api/v1/contact}.
     */
    public record ContactRequest(
            String name,
            String email,
            String subject,
            String message) {}

    /** Success response. */
    public record ContactResponse(boolean delivered) {}
}
