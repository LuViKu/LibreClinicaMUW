/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.helper;

import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Phase E.0 (2026-05-30): exposes the {@code ssoProperties} and
 * {@code factorService} beans as request attributes so JSPs can
 * reach them via EL ({@code ${ssoProperties.enabled}},
 * {@code ${factorService.twoFactorActivated}}, etc.).
 *
 * <p>Replaces the
 * {@link org.springframework.web.servlet.view.InternalResourceViewResolver#setExposeContextBeansAsAttributes}
 * approach that Phase D.6 used. That setting only works when the
 * {@code InternalResourceViewResolver} bean wins resolution in the
 * dispatcher's view-resolver chain — under Spring Boot 3.5 the
 * auto-configured {@code ContentNegotiatingViewResolver} consistently
 * delegates to Boot's default resolver (which has no equivalent
 * setting), so the @Bean-side {@code setExposeContextBeansAsAttributes}
 * never fired. A {@link HandlerInterceptor} at the dispatcher level
 * sidesteps the view-resolver question entirely.
 *
 * <p>Wired in {@code WebMvcConfig.addInterceptors} (via the
 * {@code DelegatingWebMvcConfiguration} the dispatcher inherits
 * from Boot's WebMvcAutoConfiguration).
 */
public class SsoConfigInterceptor implements HandlerInterceptor {

    private final SsoProperties ssoProperties;
    private final TwoFactorService factorService;

    public SsoConfigInterceptor(SsoProperties ssoProperties,
                                TwoFactorService factorService) {
        this.ssoProperties = ssoProperties;
        this.factorService = factorService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        request.setAttribute("ssoProperties", ssoProperties);
        request.setAttribute("factorService", factorService);
        return true;
    }
}
