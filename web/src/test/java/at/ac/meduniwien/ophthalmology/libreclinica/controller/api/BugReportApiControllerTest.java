/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc IT pinning the {@link BugReportApiController} wire-contract
 * surface the SPA's {@code bugReports} store consumes.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code POST /api/v1/bug-report} returns {@code 200} +
 *       {@code { delivered: true, ticketId: "BUG-..." }} on a valid
 *       authenticated submission with the mail-sender wired.</li>
 *   <li>Missing {@code title} → {@code 400} + ValidationErrorBody with
 *       a {@code title} FieldError.</li>
 *   <li>Missing {@code description} → {@code 400} + FieldError.</li>
 *   <li>{@code description} over the 5000-char cap → {@code 400}.</li>
 *   <li>Empty recipient config → {@code 503} + ValidationErrorBody
 *       pointing at the sysadmin.</li>
 * </ul>
 */
class BugReportApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWithRecipient(String recipient, OpenClinicaMailSender sender) {
        DataSource ds = mockDataSource();
        return mockMvcFor(new BugReportApiController(ds, sender, recipient));
    }

    /* ---------------------------------------------------------------------- */
    /* Happy path                                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturnsDeliveredTrueWithTicketIdOnValidRequest() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        doNothing().when(sender).sendEmail(any(), any(), any(), any(), anyBoolean());

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"title\":\"Subjects list misrenders\","
                                + "\"description\":\"After picking the eye filter the list is empty.\","
                                + "\"reproductionSteps\":\"1) open subjects\\n2) filter OD\","
                                + "\"pageUrl\":\"/app/subjects\","
                                + "\"userAgent\":\"Mozilla/5.0\"}")
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.ticketId").value(Matchers.startsWith("BUG-")));

        // The mail dispatch carries the configured recipient + a subject
        // that contains the ticket id and the title (the institutional
        // triage tooling line-matches on those tokens).
        verify(sender).sendEmail(
                eq("ops@example.org"),
                any(), // from header — derived from EmailEngine.getAdminEmail()
                Mockito.argThat(s -> s != null
                        && s.contains("[LibreClinicaMUW Bug Report]")
                        && s.contains("BUG-")
                        && s.contains("Subjects list misrenders")),
                Mockito.argThat(b -> b != null
                        && b.contains("Title: Subjects list misrenders")
                        && b.contains("Reporter: physician (7)")
                        && b.contains("Active study: none")
                        && b.contains("Page URL: /app/subjects")
                        && b.contains("Reproduction steps:")),
                eq(false));
    }

    /* ---------------------------------------------------------------------- */
    /* Validation                                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturns400WhenTitleMissing() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"description\":\"Empty subjects list.\"}")
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."))
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(Matchers.containsString("Title is required")));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void submitReturns400WhenDescriptionMissing() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"title\":\"Something is broken\"}")
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'description')].message",
                        Matchers.hasItem(Matchers.containsString("Description is required"))));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void submitReturns400WhenDescriptionTooLong() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        // 5001 chars to bust the cap.
        String tooLong = "x".repeat(5001);

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"title\":\"ok\",\"description\":\"" + tooLong + "\"}")
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'description')].message",
                        Matchers.hasItem(Matchers.containsString("5000"))));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    /* ---------------------------------------------------------------------- */
    /* Config gate                                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturns503WhenRecipientNotConfigured() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWithRecipient("", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"title\":\"bug\",\"description\":\"desc\"}")
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(Matchers.containsString("not configured")));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    /* ---------------------------------------------------------------------- */
    /* Console-entry attachment                                                */
    /* ---------------------------------------------------------------------- */

    @Test
    void bugReportEmailIncludesConsoleEntriesWhenSupplied() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        doNothing().when(sender).sendEmail(any(), any(), any(), any(), anyBoolean());

        // Three captured lines spanning the supported levels.
        String body = "{"
                + "\"title\":\"console attach\","
                + "\"description\":\"please see console\","
                + "\"consoleEntries\":["
                + "{\"level\":\"error\",\"message\":\"TypeError: x is undefined\",\"timestamp\":\"2026-06-11T09:00:00Z\"},"
                + "{\"level\":\"warn\",\"message\":\"deprecated API foo\",\"timestamp\":\"2026-06-11T09:00:01Z\"},"
                + "{\"level\":\"uncaught\",\"message\":\"Error in component setup\",\"timestamp\":\"2026-06-11T09:00:02Z\"}"
                + "]}";

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content(body)
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isOk());

        verify(sender).sendEmail(
                eq("ops@example.org"),
                any(),
                any(),
                Mockito.argThat(b -> b != null
                        && b.contains("Recent console output (3):")
                        && b.contains("[2026-06-11T09:00:00Z]  error  TypeError: x is undefined")
                        && b.contains("[2026-06-11T09:00:01Z]  warn  deprecated API foo")
                        && b.contains("[2026-06-11T09:00:02Z]  uncaught  Error in component setup")),
                eq(false));
    }

    @Test
    void bugReportEmailOmitsConsoleSectionWhenEntriesEmpty() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        doNothing().when(sender).sendEmail(any(), any(), any(), any(), anyBoolean());

        // Explicit empty list — same shape the SPA emits when the operator
        // unchecks the "attach console" toggle and the store still passes
        // an empty array. The body must not gain the section header.
        String body = "{"
                + "\"title\":\"no console\","
                + "\"description\":\"nothing interesting in the console\","
                + "\"consoleEntries\":[]"
                + "}";

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content(body)
                        .session((MockHttpSession)
                                authenticatedSessionWithoutStudy(7, "physician")))
                .andExpect(status().isOk());

        verify(sender).sendEmail(
                eq("ops@example.org"),
                any(),
                any(),
                Mockito.argThat(b -> b != null
                        && !b.contains("Recent console output")),
                eq(false));
    }

    @Test
    void submitReturns401WhenAnonymous() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWithRecipient("ops@example.org", sender)
                .perform(post("/api/v1/bug-report")
                        .contentType("application/json")
                        .content("{\"title\":\"bug\",\"description\":\"desc\"}")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }
}
