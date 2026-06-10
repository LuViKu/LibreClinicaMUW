/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Phase E-hardening B1 (2026-06-10) — MockMvc test pinning the
 * {@code /actuator/prometheus} contract.
 *
 * <p>This test deliberately avoids a full {@code @SpringBootTest}
 * context. The legacy {@code core/src/main/resources/logback.xml}
 * declares ~14 {@code RollingFileAppender}s that all expand to the
 * same {@code fileNamePattern} when {@code ${catalina.home}} is
 * undefined (production runs under Tomcat where it IS defined);
 * logback 1.5+ refuses to start with a "Collisions detected with
 * FileAppender/RollingAppender instances defined earlier. Aborting."
 * error during Spring Boot's {@code LoggingApplicationListener} init.
 * The lightweight {@code MockMvcBuilders.standaloneSetup} pattern
 * used by every other {@code web/} MockMvc test sidesteps Boot's
 * logging system entirely.
 *
 * <h2>Construction</h2>
 * <ol>
 *   <li>Build a {@link PrometheusMeterRegistry} the same way Boot's
 *       autoconfig does — default {@link PrometheusConfig}.</li>
 *   <li>Bind {@link JvmMemoryMetrics} to populate the registry with
 *       the same JVM metrics Boot's autoconfig binds (the
 *       {@code # HELP jvm_memory_used_bytes ...} line is the canonical
 *       assertion target).</li>
 *   <li>Expose the registry via a thin {@link ScrapeController} whose
 *       {@code @GetMapping("/actuator/prometheus")} returns
 *       {@code PrometheusMeterRegistry.scrape()} — exactly what Boot's
 *       {@code PrometheusScrapeEndpoint} does under the hood.</li>
 *   <li>Hit it via standalone MockMvc.</li>
 * </ol>
 *
 * <p>The assertions are intentionally narrow:
 * <ol>
 *   <li>{@code HTTP 200} on {@code GET /actuator/prometheus},</li>
 *   <li>body contains at least one {@code # HELP ...} line — the
 *       Prometheus text-exposition format header that every metric
 *       emits.</li>
 * </ol>
 *
 * <p>The exact metric names + content-type are NOT pinned — Micrometer
 * version bumps may rename JVM/process metrics, and the OpenMetrics 1.0
 * content-type negotiation is intentionally left flexible.
 */
class PrometheusActuatorTest {

    @Test
    void prometheusEndpointReturns200WithHelpHeader() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ScrapeController(registry))
                .build();

        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("# HELP")))
                .andReturn();
        // Defensive: explicit body check produces a more readable
        // failure message if the registry is wired but emits no
        // metrics (the andExpect chain already guards this, but the
        // assertThat surfaces the body in the stack trace).
        String body = result.getResponse().getContentAsString();
        assertThat(body, containsString("# HELP"));
    }

    /**
     * Thin stand-in for Boot's {@code PrometheusScrapeEndpoint}.
     * The autoconfig wires the same call shape: a GET handler that
     * delegates to {@link PrometheusMeterRegistry#scrape()}. We
     * reproduce the wire contract by hand so the test does not need
     * a full Boot context.
     */
    @RestController
    static class ScrapeController {

        private final PrometheusMeterRegistry registry;

        ScrapeController(PrometheusMeterRegistry registry) {
            this.registry = registry;
        }

        @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
        public String scrape() {
            return registry.scrape();
        }
    }
}
