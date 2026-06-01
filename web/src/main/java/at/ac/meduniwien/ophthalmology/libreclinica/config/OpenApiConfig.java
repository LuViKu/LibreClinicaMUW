/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase E.5 B3 — springdoc-openapi configuration.
 *
 * <p>Exposes the OpenAPI 3.x JSON spec at
 * {@code /LibreClinica/v3/api-docs} and the Swagger UI at
 * {@code /LibreClinica/swagger-ui.html}. The SPA's
 * {@code pnpm run codegen:openapi} step fetches the JSON during
 * build to refresh {@code web/src/spa/src/types/api.ts}.
 *
 * <p>{@link GroupedOpenApi} scopes the spec to
 * {@code at.ac.meduniwien.ophthalmology.libreclinica.controller.api.*}
 * — the Phase E.4 REST adapters. The 295 legacy MVC controllers
 * (Spring MVC with JSP responses) are excluded; their wire format
 * is HTML, not JSON, so an OpenAPI description would be misleading.
 *
 * <p><strong>Known limitation — empty paths on the auto-configured
 * endpoint:</strong> springdoc-openapi auto-configures into the
 * ROOT {@code WebApplicationContext} where Boot's
 * {@code @SpringBootApplication} lives, but the {@code /api/v1/*}
 * REST controllers are registered in the {@code pages}
 * {@code DispatcherServlet}'s CHILD context (per the legacy
 * two-dispatcher architecture). springdoc introspects the root
 * context's {@code RequestMappingHandlerMapping} only — child
 * mappings are invisible — so the auto-generated paths come back
 * empty until a follow-up wires springdoc's {@code OpenApiResource}
 * beans directly into the pages dispatcher's context. The
 * infrastructure here (spec endpoint publicly accessible,
 * controllers annotated with {@code @Tag}, codegen script wired
 * in {@code package.json}) is complete; the follow-up is a small
 * change scoped to {@code WebMvcConfig}. Tracked in the B3 commit.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "LibreClinica MUW SPA backend",
                version = "1.4.0rc1-muw",
                description = "Phase E.4 REST adapters consumed by the Vue 3 SPA. "
                        + "All endpoints are gated by chain-level "
                        + "`.anyRequest().hasRole(\"USER\")` and require a "
                        + "session-bound active study (POST /me/activeStudy) "
                        + "before they will return non-401 / non-400 responses.",
                contact = @Contact(
                        name = "Department of Ophthalmology and Optometry, Medical University of Vienna",
                        url = "https://augenklinik.meduniwien.ac.at/"
                ),
                license = @License(
                        name = "GNU Lesser General Public License v3 or later",
                        url = "https://libreclinica.org/license"
                )
        ),
        servers = {
                @Server(url = "/LibreClinica", description = "Same-origin (dev compose + production)")
        }
)
public class OpenApiConfig {

    /**
     * Group narrows the auto-generated spec to the Phase E.4 REST
     * adapters. Anything outside {@code controller.api.*} (legacy
     * Spring MVC controllers, the SDV/CRF JSP-driven controllers,
     * the actuator endpoints) stays out of the SPA-facing spec.
     */
    @Bean
    public GroupedOpenApi spaApi() {
        return GroupedOpenApi.builder()
                .group("spa-api")
                .packagesToScan("at.ac.meduniwien.ophthalmology.libreclinica.controller.api")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
