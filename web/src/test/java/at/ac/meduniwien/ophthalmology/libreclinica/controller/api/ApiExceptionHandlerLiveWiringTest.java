/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import at.ac.meduniwien.ophthalmology.libreclinica.webmvc.PagesExceptionResolverConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.webmvc.WebMvcConfig;

/**
 * Pins the way {@link ApiExceptionHandler} fails (or works) in PRODUCTION,
 * which {@code standaloneSetup(...).setControllerAdvice(...)} cannot show.
 *
 * <p>Production topology: the root context carries Boot's
 * {@code WebMvcAutoConfiguration} (with its own HandlerExceptionResolver
 * composite) and the {@code pages} DispatcherServlet's child context carries
 * {@link WebMvcConfig}'s hand-built handler mapping/adapter, the controllers
 * and the advice. Here the root has the same DelegatingWebMvcConfiguration as Boot (via @EnableWebMvc) plus DefaultErrorAttributes,
 * the child is wired from the real {@link WebMvcConfig} adapter bean and the
 * real {@link PagesExceptionResolverConfig}, and the request goes through a
 * DispatcherServlet bound to the child. (The full WebMvcConfig cannot be
 * loaded in a unit test: its component scan pulls in every controller.)
 */
class ApiExceptionHandlerLiveWiringTest {

    @RestController
    static class ProbeController {
        @PostMapping("/pages/api/v1/probe/body")
        Map<String, String> body(@RequestBody Map<String, String> in) {
            return in;
        }

        @GetMapping("/pages/api/v1/probe/param")
        String param(@RequestParam("n") int n) {
            return "n=" + n;
        }

        @GetMapping("/pages/api/v1/probe/missing")
        String missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing");
        }

        @GetMapping("/pages/api/v1/probe/boom")
        String boom() {
            throw new IllegalStateException("secret internals");
        }
    }

    /**
     * The root context. Boot's WebMvcAutoConfiguration extends the same
     * DelegatingWebMvcConfiguration that @EnableWebMvc imports, and adds
     * DefaultErrorAttributes (itself a HandlerExceptionResolver) via
     * ErrorMvcAutoConfiguration - the two resolver sources that matter here.
     */
    @Configuration
    @EnableWebMvc
    static class RootLikeBoot {
        @Bean
        DefaultErrorAttributes errorAttributes() {
            return new DefaultErrorAttributes();
        }
    }

    @Configuration
    @Import(PagesExceptionResolverConfig.class)
    static class ChildWithFix extends ChildBase {
    }

    @Configuration
    static class ChildWithoutFix extends ChildBase {
    }

    /** What WebMvcConfig provides to the child, minus the heavy component scan. */
    static class ChildBase {
        @Bean
        MappingJackson2HttpMessageConverter jacksonMessageConverter() {
            MappingJackson2HttpMessageConverter mc = new MappingJackson2HttpMessageConverter();
            mc.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
            return mc;
        }

        @Bean
        RequestMappingHandlerMapping requestMappingHandlerMapping() {
            RequestMappingHandlerMapping m = new RequestMappingHandlerMapping();
            m.setOrder(0);
            return m;
        }

        @Bean
        RequestMappingHandlerAdapter requestMappingHandlerAdapter(
                @Qualifier("jacksonMessageConverter") MappingJackson2HttpMessageConverter jackson) {
            WebMvcConfig cfg = new WebMvcConfig();
            return cfg.requestMappingHandlerAdapter(
                    cfg.marshallingHttpMessageConverter(cfg.jaxbMarshaller()), jackson);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @FunctionalInterface
    private interface MvcTest {
        void run(MockMvc mvc) throws Exception;
    }

    private static void withPagesDispatcher(Class<?> childConfig, MvcTest test) {
        MockServletContext servletContext = new MockServletContext();
        AnnotationConfigWebApplicationContext root = new AnnotationConfigWebApplicationContext();
        root.setServletContext(servletContext);
        root.register(RootLikeBoot.class);
        root.refresh();
        AnnotationConfigWebApplicationContext child = new AnnotationConfigWebApplicationContext();
        child.setParent(root);
        child.setServletContext(servletContext);
        child.register(childConfig);
        child.refresh();
        try {
            test.run(MockMvcBuilders.webAppContextSetup(child).build());
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            child.close();
            root.close();
        }
    }

    @Test
    void unreadableBodyGetsTheAdvice400() {
        withPagesDispatcher(ChildWithFix.class, mvc -> mvc
                .perform(post("/pages/api/v1/probe/body")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request body."))
                .andExpect(jsonPath("$.errors").isArray()));
    }

    @Test
    void emptyBodyGetsTheAdvice400WithoutLeakingTheControllerSignature() {
        withPagesDispatcher(ChildWithFix.class, mvc -> {
            MvcResult r = mvc.perform(post("/pages/api/v1/probe/body")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed or missing request body."))
                    .andReturn();
            assertFalse(r.getResponse().getContentAsString().contains("ProbeController"));
        });
    }

    @Test
    void missingAndMistypedParameterGetTheAdvice400() {
        withPagesDispatcher(ChildWithFix.class, mvc -> {
            mvc.perform(get("/pages/api/v1/probe/param"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Missing required request parameter: 'n'."));
            mvc.perform(get("/pages/api/v1/probe/param").param("n", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid value for parameter 'n'."));
        });
    }

    @Test
    void statusCarryingExceptionsKeepTheirStatus() {
        withPagesDispatcher(ChildWithFix.class, mvc -> mvc
                .perform(get("/pages/api/v1/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("no such thing")));
    }

    @Test
    void unexpectedExceptionIsGeneric500WithoutInternals() {
        withPagesDispatcher(ChildWithFix.class, mvc -> {
            MvcResult r = mvc.perform(get("/pages/api/v1/probe/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Internal server error."))
                    .andReturn();
            assertFalse(r.getResponse().getContentAsString().contains("secret"));
        });
    }

    /**
     * Control: the topology without the fix reproduces the production
     * defect (status set by DefaultHandlerExceptionResolver, advice body
     * never written). If this ever starts failing, Boot or Spring changed
     * how resolvers are discovered and the fix needs re-evaluating.
     */
    @Test
    void withoutThePagesResolverTheAdviceIsInert() {
        withPagesDispatcher(ChildWithoutFix.class, mvc -> {
            MvcResult r = mvc.perform(post("/pages/api/v1/probe/body")
                            .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andReturn();
            assertEquals(400, r.getResponse().getStatus());
            assertEquals("", r.getResponse().getContentAsString(),
                    "advice body must NOT be present without the pages resolver");
        });
    }
}
