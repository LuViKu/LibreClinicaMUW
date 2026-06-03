/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Phase E.5 (2026-06-03) — SPA history-mode fallback resolver.
 *
 * <p>The Vue 3 SPA at {@code /LibreClinica/app/*} uses vue-router's
 * {@code createWebHistory('/LibreClinica/app/')} (HTML5 history mode),
 * which means routes like {@code /app/login}, {@code /app/home},
 * {@code /app/subjects/M-001} are URLs the user types or links to,
 * but only {@code /app/index.html} actually exists on disk. The
 * router resolves the route client-side once the bundle loads.
 *
 * <p>Without this config, Tomcat's default servlet returns 404 for
 * any {@code /app/*} path that doesn't map to a real file under
 * {@code webapp/app/} — so direct-navigating to {@code /app/login}
 * or refreshing the browser on {@code /app/subjects/M-001} would
 * 404 before the SPA could load.
 *
 * <p>This {@link WebMvcConfigurer} registers a resource handler at
 * {@code /app/**} backed by a {@link PathResourceResolver} that
 * checks whether the requested sub-path actually exists on the
 * classpath / webapp root, and falls back to {@code /app/index.html}
 * when it doesn't. Standard SPA history-mode pattern; matches the
 * shape used by every Vue / React / Angular SPA deployed under a
 * non-root context.
 *
 * <p>Security: the {@code /app/**} ant-paths permitAll rule in
 * {@link SecurityConfig} runs first; the SPA bundle is anonymous-
 * readable (no PHI, no secrets in the bundle, all API calls remain
 * auth-gated independently). The fallback resolver here just
 * decides which static file backs the request; it doesn't change
 * the authorization model.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // /app and /app/ both forward to the SPA's index.html.
        // Without this, hitting the bare /app/ root URL 404s because
        // there's no default welcome-file mapping for the /app subtree
        // — the resource handler below only matches /app/<something>.
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations("/app/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Any path under /app/ that isn't a real file
                        // (i.e. SPA history-mode routes like /app/login,
                        // /app/home, /app/subjects/M-001) falls through
                        // to the SPA's index.html — vue-router handles
                        // the rest in the browser.
                        Resource indexHtml = location.createRelative("index.html");
                        return indexHtml.exists() && indexHtml.isReadable() ? indexHtml : null;
                    }
                });
    }
}
