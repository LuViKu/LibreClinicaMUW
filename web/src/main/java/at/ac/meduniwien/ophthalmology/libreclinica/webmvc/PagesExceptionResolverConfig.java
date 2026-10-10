/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.webmvc;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * Makes {@code @ControllerAdvice} beans of the {@code pages} child context
 * effective on the {@code pages} DispatcherServlet.
 *
 * <p>Why it was inert: the DispatcherServlet only falls back to its default
 * {@code ExceptionHandlerExceptionResolver} when it finds <em>no</em>
 * {@code HandlerExceptionResolver} bean, and it looks through the parent
 * context too. Boot's {@code WebMvcAutoConfiguration} (root context)
 * registers a composite resolver with its own
 * {@code ExceptionHandlerExceptionResolver}, which only scans advice beans
 * of the root context. {@code ApiExceptionHandler} lives in the child
 * (scanned by {@link WebMvcConfig}), so that resolver never saw it and
 * {@code DefaultHandlerExceptionResolver} answered instead.
 *
 * <p>This resolver is created inside the child, so it sees the child's
 * advice beans, and is ordered ahead of the root composite. Anything it
 * does not handle falls through to the root resolvers unchanged.
 */
@Configuration
public class PagesExceptionResolverConfig {

    @Bean
    public ExceptionHandlerExceptionResolver pagesExceptionHandlerExceptionResolver(
            @Qualifier("jacksonMessageConverter") JacksonJsonHttpMessageConverter jacksonMessageConverter) {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        // Same converters (and so the same Jackson settings) as the
        // handler adapter in WebMvcConfig.
        resolver.setMessageConverters(List.of(new ByteArrayHttpMessageConverter(), jacksonMessageConverter));
        resolver.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return resolver;
    }
}
