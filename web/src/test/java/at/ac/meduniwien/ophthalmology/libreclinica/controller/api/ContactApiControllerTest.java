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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.8 Slice L2 (2026-06-20) — MockMvc IT for {@link
 * ContactApiController}. Drives every branch that's reachable without
 * a Spring container:
 *
 * <ul>
 *   <li>200 happy path → mail sender invoked with the resolved admin
 *       recipient + the sender-supplied address as the from header +
 *       the prefixed subject line.</li>
 *   <li>400 + per-field FieldErrors on missing / invalid inputs.</li>
 *   <li>503 when {@link EmailEngine#getAdminEmail} resolves to blank
 *       (the production failure mode when the static initialiser hasn't
 *       run yet) — must NOT silently swallow the message.</li>
 *   <li>500 when the mail sender throws.</li>
 * </ul>
 */
class ContactApiControllerTest extends AbstractApiControllerTest {

    /**
     * Test-double that returns a configurable admin-email instead of
     * touching the {@link EmailEngine#getAdminEmail} static path. The
     * production class uses an overridable {@code resolveAdminEmail}
     * hook precisely so the test can avoid booting CoreResources.
     */
    private static final class TestableContactApiController extends ContactApiController {
        private final String resolvedAdminEmail;

        TestableContactApiController(OpenClinicaMailSender sender, String resolvedAdminEmail) {
            super(sender);
            this.resolvedAdminEmail = resolvedAdminEmail;
        }

        @Override
        protected String resolveAdminEmail() {
            return resolvedAdminEmail;
        }
    }

    private MockMvc mockMvcWith(String adminEmail, OpenClinicaMailSender sender) {
        return mockMvcFor(new TestableContactApiController(sender, adminEmail));
    }

    /* ---------------------------------------------------------------------- */
    /* Happy path                                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturns200AndDispatchesMailOnValidRequest() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        doNothing().when(sender).sendEmail(any(), any(), any(), any(), anyBoolean());

        mockMvcWith("admin@example.org", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"Anne Tester\","
                                + "\"email\":\"anne@example.org\","
                                + "\"subject\":\"CRF rendering issue\","
                                + "\"message\":\"I see a blank panel on visit 3.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true));

        // From-address must be the sender's email so the institutional
        // inbox can reply directly; subject must carry the topic prefix.
        verify(sender).sendEmail(
                eq("admin@example.org"),
                eq("anne@example.org"),
                Mockito.argThat(s -> s.startsWith("[LibreClinicaMUW Contact]") &&
                        s.contains("CRF rendering issue")),
                Mockito.argThat(b -> b.contains("Anne Tester") &&
                        b.contains("blank panel on visit 3")),
                eq(false));
    }

    /* ---------------------------------------------------------------------- */
    /* Validation                                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturns400WithFieldErrorOnMissingName() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWith("admin@example.org", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"email\":\"a@b.io\",\"subject\":\"x\",\"message\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message")
                        .value(Matchers.hasItem(Matchers.containsString("required"))));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void submitReturns400OnMalformedEmail() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWith("admin@example.org", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"a\",\"email\":\"not-an-email\","
                                + "\"subject\":\"x\",\"message\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='email')].message")
                        .value(Matchers.hasItem(Matchers.containsString("valid"))));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void submitReturns400WhenMessageExceedsCap() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        String huge = "x".repeat(5001);

        mockMvcWith("admin@example.org", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"a\",\"email\":\"a@b.io\","
                                + "\"subject\":\"x\",\"message\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='message')]")
                        .value(Matchers.not(Matchers.empty())));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    /* ---------------------------------------------------------------------- */
    /* Recipient + transport failure                                           */
    /* ---------------------------------------------------------------------- */

    @Test
    void submitReturns503WhenRecipientUnresolved() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);

        mockMvcWith("", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"a\",\"email\":\"a@b.io\","
                                + "\"subject\":\"x\",\"message\":\"y\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(Matchers.containsString("sysadmin")));

        verify(sender, never()).sendEmail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void submitReturns500WhenMailSenderThrows() throws Exception {
        OpenClinicaMailSender sender = Mockito.mock(OpenClinicaMailSender.class);
        doThrow(new RuntimeException("smtp blew up"))
                .when(sender).sendEmail(any(), any(), any(), any(), anyBoolean());

        mockMvcWith("admin@example.org", sender)
                .perform(post("/api/v1/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"a\",\"email\":\"a@b.io\","
                                + "\"subject\":\"x\",\"message\":\"y\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(Matchers.containsString("Failed to dispatch")));
    }
}
