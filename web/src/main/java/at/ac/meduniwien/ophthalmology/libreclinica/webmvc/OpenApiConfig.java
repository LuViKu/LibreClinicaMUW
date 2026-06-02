/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.webmvc;

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
 * {@code /LibreClinica/pages/v3/api-docs} and the Swagger UI at
 * {@code /LibreClinica/pages/swagger-ui.html} — the {@code /pages/}
 * prefix matches the dispatcher whose child context holds the
 * springdoc beans (see follow-up note below). The SPA's
 * {@code pnpm run codegen:openapi} step fetches the JSON during
 * build to refresh {@code web/src/spa/src/types/api.ts}.
 *
 * <p>{@link GroupedOpenApi} scopes the spec to
 * {@code at.ac.meduniwien.ophthalmology.libreclinica.controller.api.*}
 * — the Phase E.4 REST adapters. The 295 legacy MVC controllers
 * (Spring MVC with JSP responses) are excluded; their wire format
 * is HTML, not JSON, so an OpenAPI description would be misleading.
 *
 * <p><strong>Two-dispatcher wiring (Phase E.5 follow-up, 2026-06-01):
 * </strong> springdoc-openapi's bean-creation auto-configurations
 * ({@link org.springdoc.core.configuration.SpringDocConfiguration},
 * {@link org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration},
 * {@link org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration},
 * {@link org.springdoc.webmvc.ui.SwaggerConfig}) are
 * {@link org.springframework.boot.autoconfigure.SpringBootApplication#exclude excluded}
 * from the ROOT {@code ApplicationContext} in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.LibreClinicaApplication}
 * and {@link org.springframework.context.annotation.Import @Import}-ed
 * by {@link at.ac.meduniwien.ophthalmology.libreclinica.webmvc.WebMvcConfig}
 * instead — so {@link org.springdoc.webmvc.api.OpenApiResource} and
 * {@link org.springdoc.webmvc.core.providers.SpringWebMvcProvider} land
 * in the {@code pages} {@code DispatcherServlet}'s CHILD context, the
 * only context where the 10 {@code /api/v1/**} {@code @RestController}
 * classes register their request mappings. Property holders
 * ({@link org.springdoc.core.properties.SpringDocConfigProperties},
 * {@link org.springdoc.core.properties.SwaggerUiConfigProperties},
 * {@link org.springdoc.core.properties.SwaggerUiOAuthProperties})
 * are registered alongside in the child context via
 * {@code @EnableConfigurationProperties} on {@code WebMvcConfig} — they
 * are {@code @ConfigurationProperties} classes; plain {@code @Import}
 * skips Boot's property binder so downstream {@code @Bean} methods
 * autowiring them fail with {@code NoSuchBeanDefinitionException}.
 *
 * <p>The public URL for the spec is {@code /LibreClinica/pages/v3/api-docs/spa-api}
 * — {@code /pages/*} routes to the dispatcher hosting OpenApiResource,
 * which consumes {@code /pages} as its servletPath and hands the
 * handler-mapping the pathInfo {@code /v3/api-docs/spa-api}. That matches
 * OpenApiResource's default {@code @RequestMapping("${springdoc.api-docs.path:/v3/api-docs}/{group}")}.
 * The {@code springdoc.api-docs.path} property therefore stays at its
 * default — setting it to e.g. {@code /pages/v3/api-docs} would register
 * the handler at that pattern, but the dispatcher's post-strip lookup is
 * still {@code /v3/api-docs/spa-api} and would never match. The
 * {@link Server#url() server URL} below is set to
 * {@code /LibreClinica/pages} so the generated spec's relative paths
 * resolve correctly when called from a SPA client.
 *
 * <p>Background and root cause are documented in
 * {@code docs/development/modernization/phase-e/springdoc-pages-dispatcher.md}.
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
                @Server(url = "/LibreClinica/pages", description = "Same-origin (dev compose + production). The /pages prefix is the legacy pages-DispatcherServlet mount; every /api/v1/** controller in the generated spec lives under it.")
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
