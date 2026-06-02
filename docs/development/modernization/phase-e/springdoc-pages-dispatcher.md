# Phase E.5 follow-up: springdoc-openapi in the pages-dispatcher child context

**Status:** Landed 2026-06-01 (`feature/muw-phase-e5-springdoc-typegen`).
**Closes:** the "Known limitation — empty paths" caveat noted in PR #52's `OpenApiConfig.java` javadoc.

## Symptom

After PR #52 wired springdoc-openapi-starter-webmvc-ui 2.6.0 into the build and added `@Tag` / `@Operation` annotations to the 10 `controller/api/**` `@RestController` classes, curling the spec endpoint returned an OpenAPI document with `"paths": {}` and `"components": {}`:

```sh
curl -s http://127.0.0.1:8080/LibreClinica/v3/api-docs/spa-api | jq '.paths | length'
# 0
```

The SPA's `pnpm run codegen:openapi` step therefore produced an empty `web/src/spa/src/types/api.ts` — `paths` and `schemas` typed as `never`, defeating the whole TS-from-OpenAPI feedback loop.

## Root cause

LibreClinica MUW runs **two `DispatcherServlet`s**:

| Servlet | Mount | Context | What's in it |
|---------|-------|---------|--------------|
| Boot's auto-registered `dispatcherServlet` | `/` | ROOT `WebApplicationContext` | `@SpringBootApplication` scan of `.config`, autoconfig beans, actuator endpoints |
| Legacy `pages` `DispatcherServlet` | `/pages/*` + `/oauth/*` | CHILD context bootstrapped from `pages-servlet.xml` → `WebMvcConfig` | The 10 `/api/v1/**` REST controllers + 295 legacy MVC controllers (`@ComponentScan(".controller")`) |

Springdoc's WebMVC handler-provider (`SpringWebMvcProvider`) discovers controllers via:

```java
applicationContext.getBeansOfType(RequestMappingHandlerMapping.class)
```

`getBeansOfType` is **context-local** — it does not walk DOWN the hierarchy into child contexts. Springdoc's auto-config registered its beans in ROOT, so the lookup found Boot's root-context `RequestMappingHandlerMapping` (which only knows about `/actuator/*` and `/error`). The 10 `/api/v1/**` mappings were invisible.

## Fix

Move springdoc's bean-creation configurations into the pages dispatcher's child context, and configure the spec/UI paths under the dispatcher's existing `/pages/*` prefix so the URLs route there without any new `<servlet-mapping>`.

### 1. Relocate the springdoc beans

**[`LibreClinicaApplication.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/LibreClinicaApplication.java)** — exclude the bean-creation configs from `@SpringBootApplication`'s autoconfig set so they do NOT register in the root context:

  - `SpringDocConfiguration` (core service beans: `OpenAPIService`, `OperationService`, `RequestBodyService`, `SpringDocProviders`, customizers, etc.)
  - `SpringDocWebMvcConfiguration` (`OpenApiWebMvcResource`, the WebMVC `RequestService`, `SpringWebMvcProvider`)
  - `MultipleOpenApiSupportConfiguration` (`MultipleOpenApiWebMvcResource` — the `@RestController` serving `/{springdoc.api-docs.path}/{group}`)
  - `SwaggerConfig` (Swagger UI welcome page + resource handlers — symmetric move so the UI continues to find the spec). The legacy `SpringDocUIConfiguration` companion class was merged away in springdoc 2.8.

**[`WebMvcConfig.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/webmvc/WebMvcConfig.java)** — `@Import` the same five `@Configuration` classes so their `@Bean` methods execute inside the child context, with the child's `RequestMappingHandlerMapping` visible to `getBeansOfType`.

### 2. Bind springdoc's @ConfigurationProperties in the child too

The three property classes — `SpringDocConfigProperties`, `SwaggerUiConfigProperties`, `SwaggerUiOAuthProperties` — are `@ConfigurationProperties` + `@Configuration` hybrids. Plain `@Import` registers the bean *definition* but does NOT trigger Spring Boot's `ConfigurationPropertiesBindingPostProcessor`, so any downstream `@Bean` autowiring them fails with `NoSuchBeanDefinitionException`. The right machinery is `@EnableConfigurationProperties`:

```java
@EnableConfigurationProperties({
    SpringDocConfigProperties.class,
    SwaggerUiConfigProperties.class,
    SwaggerUiOAuthProperties.class
})
```

This binds the properties from the `Environment` and registers concrete instances under predictable bean names — `SpringDocConfiguration$QuerydslProvider#queryDslQuerydslPredicateOperationCustomizer` (which autowires `SpringDocConfigProperties`) then resolves cleanly.

### 3. Align the URL prefix with the dispatcher's prefix

Configure springdoc to mount under `/pages/` in **[`application.yml`](../../../../web/src/main/resources/application.yml)** instead of the default root paths:

```yaml
springdoc:
  api-docs:
    path: /pages/v3/api-docs
  swagger-ui:
    path: /pages/swagger-ui.html
```

This is the key insight behind avoiding a `web.xml` workaround. Earlier attempts added `<servlet-mapping>` entries routing `/v3/api-docs/*` to the `pages` dispatcher; Tomcat does honour the mapping, but a `DispatcherServlet` mapped via path-prefix STRIPS its prefix before handler lookup — so OpenApiResource's `@RequestMapping("${springdoc.api-docs.path:/v3/api-docs}/{group}")` could never match (the lookup arrived at the handler-mapping as just `/{group}`). Mounting springdoc under `/pages/*` lets the request flow through the dispatcher whose `/pages/*` mapping already exists and whose handler-mapping does see the controllers.

### 4. Permit the new prefix in SecurityConfig

**[`SecurityConfig.java`](../../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/SecurityConfig.java)** permits both prefixes — the legacy `/v3/api-docs/**` (now 404s but harmless) and the new `/pages/v3/api-docs/**` (active). The spec contains no clinical data — just the API surface description — so `.permitAll()` is appropriate; the SPA's `pnpm run codegen:openapi` step + ops sanity checks run without an auth session.

### 5. Point the codegen URL at the new prefix

**[`web/src/spa/package.json`](../../../../web/src/spa/package.json):**

```json
"codegen:openapi": "openapi-typescript http://127.0.0.1:8080/LibreClinica/pages/v3/api-docs/spa-api -o src/types/api.ts"
```

**[`.github/workflows/build.yml`](../../../../.github/workflows/build.yml):** the CI drift guard now (a) fetches from `/pages/v3/api-docs/spa-api` and (b) asserts the response's `paths` object is non-empty (the previous "tolerated empty-paths" comment is removed — empty paths is now a regression).

## Verification

After `docker compose up --build -d`:

```sh
curl -s http://127.0.0.1:8080/LibreClinica/pages/v3/api-docs/spa-api | jq '.paths | keys | length'
# > 10
curl -s http://127.0.0.1:8080/LibreClinica/pages/v3/api-docs/spa-api | jq '.components.schemas | keys | length'
# > 10
```

The first call returns every `/api/v1/**` endpoint with `operationId`, `parameters`, `responses`. The second returns every DTO record from `controller.api.*`. The SPA's `pnpm run codegen:openapi` regenerates `web/src/spa/src/types/api.ts` with real `paths` and `components.schemas` (~tens of kilobytes); the drift guard in `.github/workflows/build.yml` (PR #54 TODO #9) keeps it that way.

## Why not move the REST controllers to root instead?

Tempting alternative: scan `controller.api.**` from `.config` (so the REST controllers register in root) and leave the 295 legacy MVC controllers in the pages context. Rejected because:

- The REST controllers share helpers and `@Autowired` collaborators (`UserAccountDAO`, `StudyDAO`, the OpenClinica session beans) wired in the child context. Splitting them would force a wider component-scan reshuffling.
- The `@ControllerAdvice` (`ApiExceptionHandler`) and the JSON `MappingJackson2HttpMessageConverter` are configured against the pages dispatcher's `RequestMappingHandlerAdapter` with an explicit converter list. Cross-context wiring would lose that.
- Moving springdoc is a tight `@Import` + `@EnableConfigurationProperties` + two YAML properties; moving controllers is a refactor that touches every Phase E.4 wiring decision (DR-013).

## References

- DR-013 — Phase E.4 REST adapter wiring (controllers live in pages-child context).
- DR-014 — institutional SSO + the e-signature reauth endpoint that established the `/pages/sso/reauth` precedent (root-context bean wouldn't find the child-context collaborators).
- PR #52 — initial springdoc B3 integration that surfaced the limitation.
- PR #54 — TODO #9 OpenAPI codegen drift guard in CI (now asserts non-empty paths).
