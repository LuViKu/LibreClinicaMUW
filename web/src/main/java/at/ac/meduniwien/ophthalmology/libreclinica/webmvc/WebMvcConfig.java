package at.ac.meduniwien.ophthalmology.libreclinica.webmvc;

import java.util.List;

import javax.sql.DataSource;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MarshallingHttpMessageConverter;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter;
import org.springframework.web.servlet.mvc.UrlFilenameViewController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.SidebarEnumConstants;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.SidebarInit;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.helper.SetUpUserInterceptor;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.helper.SsoConfigInterceptor;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;

/**
 * Phase C.11 (2026-05-30): Java replacement for {@code pages-servlet.xml}.
 * <p>
 * Loaded by the {@code pages} {@link org.springframework.web.servlet.DispatcherServlet}
 * registered in {@code web.xml} — its child context bootstraps this
 * configuration via the (now-empty-of-beans) {@code pages-servlet.xml}
 * which delegates to this class via {@code <bean class="WebMvcConfig"/>}.
 * <p>
 * Bean shape preserved verbatim:
 * <ul>
 *   <li>{@code /login/login} + {@code /denied} {@link UrlFilenameViewController}s
 *       (URL path substring becomes view name)</li>
 *   <li>{@link SimpleControllerHandlerAdapter} for the URL controllers above</li>
 *   <li>Component-scan of {@code .controller} package — picks up all
 *       {@code @Controller} classes</li>
 *   <li>{@link SidebarInit} (alerts/icons/info/instructions box defaults)</li>
 *   <li>{@link SetUpUserInterceptor} (loads {@code currentUser} into session)</li>
 *   <li>JAXB marshaller for CDISC ODM (xml) + Jackson converter (json) +
 *       string converter</li>
 *   <li>{@link RequestMappingHandlerMapping} + {@link RequestMappingHandlerAdapter}
 *       with explicit message-converter list</li>
 *   <li>{@link BeanNameUrlHandlerMapping} (for the {@code /login/login} bean
 *       name route above)</li>
 *   <li>{@link InternalResourceViewResolver} JSP resolver
 *       ({@code /WEB-INF/jsp/} prefix, {@code .jsp} suffix)</li>
 * </ul>
 * <p>
 * The legacy XML's commented-out {@code <mvc:annotation-driven />} stays
 * commented-out: the manual {@code RequestMappingHandlerMapping} +
 * {@code RequestMappingHandlerAdapter} below give us control over the
 * message-converter list (XML/JSON for the rules REST API), which
 * {@code @EnableWebMvc} doesn't.
 */
@Configuration
@ComponentScan("at.ac.meduniwien.ophthalmology.libreclinica.controller")
// Phase E.5 follow-up (2026-06-01): springdoc bean-creation configs are
// re-imported here so OpenApiResource + SpringWebMvcProvider land in the
// `pages` DispatcherServlet's CHILD context, the only context where the
// 10 `/api/v1/**` @RestController classes register their request mappings.
// Companion change: LibreClinicaApplication.java excludes these same
// classes from the ROOT context (otherwise we'd get duplicate beans in
// the two contexts AND springdoc's `getBeansOfType` lookup in root would
// keep returning the empty handler mapping).
//
// SpringDocConfigProperties + Swagger-UI properties are loaded here too,
// in the child context, via @EnableConfigurationProperties (NOT @Import).
// They are @ConfigurationProperties classes; @Import registers the bean
// definition but does NOT trigger Spring Boot's property binding —
// downstream @Bean methods autowiring them then fail with
// NoSuchBeanDefinitionException because the binder never created the
// concrete instance. @EnableConfigurationProperties is the right
// machinery for this. See
// docs/development/modernization/phase-e/springdoc-pages-dispatcher.md.
// Phase E.5 follow-up (2026-06-01): OpenApiConfig (with the
// `spaApi` GroupedOpenApi bean and the @OpenAPIDefinition class-level
// metadata) is imported explicitly here so it lands in the CHILD
// context alongside the springdoc machinery. Putting it in the root
// .config scan would land the GroupedOpenApi bean in root, while
// MultipleOpenApiSupportCondition (gating MultipleOpenApiWebMvcResource)
// evaluates context-locally in the child — so the multi-group spec
// endpoint would never activate and `/v3/api-docs/{group}` would 404.
// Moving the class to the .webmvc package keeps it out of root's
// component-scan; @Import wires it into the child where the rest of
// springdoc lives.
@Import({
        OpenApiConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        SwaggerConfig.class
})
@EnableConfigurationProperties({
        SpringDocConfigProperties.class,
        SwaggerUiConfigProperties.class,
        SwaggerUiOAuthProperties.class
})
public class WebMvcConfig {

    /**
     * Phase E.0 (2026-05-30): {@link SsoConfigInterceptor} bean. Wired
     * directly onto the handler mappings below (NOT via
     * {@code WebMvcConfigurer.addInterceptors} — that machinery is
     * Boot's root-context {@code DelegatingWebMvcConfiguration} and
     * does not reach the pages DispatcherServlet's child context).
     */
    @Bean
    public SsoConfigInterceptor ssoConfigInterceptor(SsoProperties ssoProperties,
                                                     @Qualifier("factorService") TwoFactorService factorService) {
        return new SsoConfigInterceptor(ssoProperties, factorService);
    }


    @Bean(name = "/login/login")
    public UrlFilenameViewController loginLoginController() {
        return new UrlFilenameViewController();
    }

    @Bean(name = "/denied")
    public UrlFilenameViewController deniedController() {
        return new UrlFilenameViewController();
    }

    @Bean
    public SimpleControllerHandlerAdapter simpleControllerHandlerAdapter() {
        return new SimpleControllerHandlerAdapter();
    }

    @Bean
    public SidebarInit sidebarInit() {
        SidebarInit s = new SidebarInit();
        s.setAlertsBoxSetup(SidebarEnumConstants.OPENALERTS);
        s.setEnableIconsBoxSetup(SidebarEnumConstants.DISABLEICONS);
        s.setInfoBoxSetup(SidebarEnumConstants.OPENINFO);
        s.setInstructionsBoxSetup(SidebarEnumConstants.OPENINSTRUCTIONS);
        return s;
    }

    @Bean
    public SetUpUserInterceptor setUpUserInterceptor(@Qualifier("dataSource") DataSource dataSource) {
        SetUpUserInterceptor i = new SetUpUserInterceptor();
        i.setDataSource(dataSource);
        return i;
    }

    @Bean
    public Jaxb2Marshaller jaxbMarshaller() {
        Jaxb2Marshaller m = new Jaxb2Marshaller();
        m.setContextPaths(
                "org.cdisc.ns.odm.v130",
                "org.cdisc.ns.odm.v130_api",
                "org.openclinica.ns.odm_ext_v130.v31_api",
                "org.openclinica.ns.odm_ext_v130.v31",
                "org.openclinica.ns.rules.v31",
                "org.openclinica.ns.response.v31",
                "org.openclinica.ns.rules_test.v31");
        return m;
    }

    @Bean
    public MarshallingHttpMessageConverter marshallingHttpMessageConverter(Jaxb2Marshaller jaxbMarshaller) {
        MarshallingHttpMessageConverter mc = new MarshallingHttpMessageConverter();
        mc.setMarshaller(jaxbMarshaller);
        mc.setUnmarshaller(jaxbMarshaller);
        mc.setSupportedMediaTypes(List.of(MediaType.APPLICATION_XML));
        return mc;
    }

    @Bean
    public StringHttpMessageConverter stringHttpMessageConverter() {
        return new StringHttpMessageConverter();
    }

    @Bean
    public MappingJackson2HttpMessageConverter jacksonMessageConverter() {
        MappingJackson2HttpMessageConverter mc = new MappingJackson2HttpMessageConverter();
        mc.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
        return mc;
    }

    @Bean
    public RequestMappingHandlerMapping requestMappingHandlerMapping(
            SsoConfigInterceptor ssoConfigInterceptor) {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        // Phase E.0 (2026-05-30): explicit order = 0 (highest priority)
        // so this beats Boot's WebMvcAutoConfiguration-provided
        // SimpleUrlHandlerMapping resource handler. Without this, the
        // resource handler at `/webjars/**, /**` (Ordered.LOWEST_PRECEDENCE-1)
        // catches every /pages/* request before @Controller @GetMapping
        // can match — every /pages/sso/reauth, /pages/odmk/*, etc.
        // returned 404 from the resource handler's NoResourceFoundException
        // path. See docs/development/modernization/phase-e/post-phase-d-ui-validation.md.
        mapping.setOrder(0);
        // Phase E.0: expose ssoProperties + factorService as request
        // attributes for JSP EL evaluation (D.6 SSO button + existing
        // 2FA conditional).
        mapping.setInterceptors(ssoConfigInterceptor);
        return mapping;
    }

    @Bean
    public RequestMappingHandlerAdapter requestMappingHandlerAdapter(
            MarshallingHttpMessageConverter marshallingHttpMessageConverter,
            // Phase D.6 (DR-014): explicit @Qualifier — Phase C.15
            // un-excluded WebMvcAutoConfiguration which registers its
            // own `mappingJackson2HttpMessageConverter` bean alongside
            // our `jacksonMessageConverter`. Without the qualifier,
            // by-type injection here was ambiguous → the `pages`
            // DispatcherServlet failed to initialise → GET
            // /pages/login/login returned HTTP 500 on every request.
            // The qualifier is the surgical fix; the root issue is
            // logged in the Phase E known-issues file. Now resolved.
            @org.springframework.beans.factory.annotation.Qualifier("jacksonMessageConverter")
                    MappingJackson2HttpMessageConverter jacksonMessageConverter) {
        RequestMappingHandlerAdapter a = new RequestMappingHandlerAdapter();
        // Phase E.5 follow-up (2026-06-01): ByteArrayHttpMessageConverter
        // ordered FIRST so springdoc's OpenApiResource.openapiJson()
        // (which returns a byte[] holding the already-serialised JSON
        // spec) lands on the wire as raw application/json, not as a
        // Jackson-base64-stringified payload. Without this converter
        // the SPA's openapi-typescript codegen fetches `"eyJ..."` and
        // fails to parse.
        a.setMessageConverters(List.of(
                new ByteArrayHttpMessageConverter(),
                marshallingHttpMessageConverter,
                jacksonMessageConverter));
        return a;
    }

    @Bean
    public BeanNameUrlHandlerMapping beanNameUrlHandlerMapping(
            SsoConfigInterceptor ssoConfigInterceptor) {
        BeanNameUrlHandlerMapping mapping = new BeanNameUrlHandlerMapping();
        // Phase E.0 (2026-05-30): explicit order = 1 so /login/login +
        // /denied beans win over Boot's resource handler
        // (Ordered.LOWEST_PRECEDENCE-1) but still come after the @Controller
        // RequestMappingHandlerMapping at order 0. Default order is
        // Ordered.LOWEST_PRECEDENCE which let the resource handler win,
        // causing /pages/login/login to 404 via NoResourceFoundException
        // instead of resolving to the UrlFilenameViewController. See
        // docs/development/modernization/phase-e/post-phase-d-ui-validation.md.
        mapping.setOrder(1);
        // Phase E.0: ssoConfigInterceptor exposes ssoProperties +
        // factorService for the login JSP EL evaluation.
        mapping.setInterceptors(ssoConfigInterceptor);
        return mapping;
    }

    /**
     * Phase E.6 (2026-06-06): multipart resolver for the CRF file-upload
     * endpoint. The legacy applicationContext-web-beans.xml does NOT
     * register one; the {@code pages} DispatcherServlet's child context
     * therefore had no {@code multipartResolver} bean, so any
     * {@code MultipartFile} parameter on a Spring MVC method would arrive
     * as {@code null}.
     * <p>
     * The bean name MUST be {@code multipartResolver} -- DispatcherServlet
     * looks the resolver up by the exact bean name during init (see
     * {@code DispatcherServlet#MULTIPART_RESOLVER_BEAN_NAME}).
     * <p>
     * {@link StandardServletMultipartResolver} delegates to the Servlet
     * 6 multipart API. The CRF file-upload controller adjusts its size
     * cap via the {@code crf.file.maxBytes} property (server-side
     * enforcement); the dispatcher's default max-request-size is left
     * unbounded here because the controller short-circuits oversize
     * uploads with a 413 before reading the body.
     */
    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }

    @Bean
    public InternalResourceViewResolver internalViewResolver() {
        InternalResourceViewResolver r = new InternalResourceViewResolver();
        r.setPrefix("/WEB-INF/jsp/");
        r.setSuffix(".jsp");
        // Phase D.6 (DR-014): expose Spring beans as JSP request
        // attributes so login.jsp can read ${ssoProperties.enabled}
        // for the institutional-SSO button visibility. The existing
        // ${factorService.twoFactorActivated} expression in the same
        // JSP relied on this pattern in the legacy XML config; this
        // line restores it under the Java config. Scoped to a
        // specific set of beans rather than exposing the entire
        // context (defence-in-depth — JSP authors can only reach
        // the listed beans via EL).
        r.setExposeContextBeansAsAttributes(true);
        r.setExposedContextBeanNames("factorService", "ssoProperties");
        // Phase E.0 (2026-05-30): explicit order so this resolver
        // wins over Boot's auto-configured InternalResourceViewResolver
        // (default order Ordered.LOWEST_PRECEDENCE). Boot's version
        // honours spring.mvc.view.prefix/.suffix from application.yml
        // but does NOT carry exposeContextBeansAsAttributes — so when
        // Boot's resolver wins, JSPs can't read ${ssoProperties.*}
        // and the D.6 SSO button stays hidden. Order 0 makes this
        // bean the sole resolver for the pages dispatcher.
        r.setOrder(0);
        return r;
    }
}
