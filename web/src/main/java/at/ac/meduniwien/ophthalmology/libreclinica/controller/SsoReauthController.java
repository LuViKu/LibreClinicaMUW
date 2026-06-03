/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Phase D.10 (DR-014): e-signature re-authentication scaffolding.
 *
 * <p>When a user is about to perform a §11.50-binding action
 * (Sign Subject, sign-off on discrepancy resolution, etc.) the
 * front-end navigates to {@code /sso/reauth?target=<original-url>}.
 * The controller issues a 302 to the configured proxy re-auth URL
 * (template in {@link SsoProperties.Reauth#getUrlTemplate}). For
 * Shibboleth the template includes {@code forceAuthn=true}; for
 * OIDC under {@code mod_auth_openidc} the template includes
 * {@code prompt=login}; for AWS ALB it can be a re-challenge URL.
 * The reverse proxy strips the existing session, re-prompts at the
 * IdP, and proxies back to the original target.
 *
 * <p><strong>Production-deferred.</strong> The Sign Subject flow
 * does NOT yet invoke this endpoint — it continues to require a
 * local password challenge as the §11.50 signature event. Per
 * DR-014 (and the playbook D.10 risk register), proxy-mediated
 * re-auth as a §11.50-compliant signature event needs explicit
 * legal/regulatory ratification first. The endpoint exists so the
 * wiring is in place when the ratification lands. The
 * {@code libreclinica.sso.reauth.enabled} flag stays
 * default-off; flipping it does NOT change today's flow
 * (Sign Subject still asks for password) — it only opens the
 * door for a future commit to call {@code /sso/reauth} from
 * the Sign Subject controller.
 *
 * <p>Permitted unauthenticated in {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig}
 * because the reverse proxy may strip the session before the
 * re-challenge — the endpoint just emits a 302 with a public URL,
 * no LibreClinica session required.
 *
 * <p><strong>URL path:</strong> {@code /pages/sso/reauth} (under the
 * legacy {@code pages} DispatcherServlet's {@code /pages/*} mapping).
 * The {@code WebMvcConfig.@ComponentScan("controller")} picks this
 * class up for the pages dispatcher's child context. Boot's
 * auto-registered dispatcher at {@code /} does NOT scan the
 * {@code controller} package (LibreClinicaApplication scans only
 * {@code .config}), so the natural shorter {@code /sso/reauth}
 * 404s; the {@code /pages} prefix lands the request on the
 * dispatcher that knows about this controller.
 */
@Controller
public class SsoReauthController {

    private static final Logger log =
            LoggerFactory.getLogger(SsoReauthController.class);

    private final SsoProperties ssoProperties;

    @Autowired
    public SsoReauthController(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    /**
     * Issues a 302 to the configured proxy re-auth URL. The
     * {@code {target}} placeholder in
     * {@link SsoProperties.Reauth#getUrlTemplate} is replaced with
     * the URL-encoded {@code target} query parameter, defaulting to
     * {@code /MainMenu} if absent.
     *
     * <p>SSO not enabled, or proxy URL template not configured:
     * returns 302 to {@code /pages/login/login} as a safe fallback
     * (re-auth via the local-password path).
     */
    @GetMapping("/sso/reauth")  // resolved relative to /pages — full URL is /pages/sso/reauth
    public RedirectView reauth(
            @RequestParam(value = "target", required = false, defaultValue = "/MainMenu")
                    String target) {
        if (!ssoProperties.isEnabled()) {
            // SSO is off — the user has no IdP session to re-challenge.
            // Fall back to the local login page.
            log.debug("/sso/reauth invoked but libreclinica.sso.enabled=false;"
                    + " redirecting to local login page");
            return contextRelativeRedirect("/pages/login/login");
        }

        String urlTemplate = ssoProperties.getReauth().getUrlTemplate();
        if (urlTemplate == null || urlTemplate.isEmpty()) {
            log.warn("/sso/reauth invoked but reauth.url-template is empty;"
                    + " redirecting to local login page");
            return contextRelativeRedirect("/pages/login/login");
        }

        String encodedTarget = URLEncoder.encode(target, StandardCharsets.UTF_8);
        String redirectUrl = urlTemplate.replace("{target}", encodedTarget);

        log.info("SSO re-auth requested for target='{}', redirecting via template",
                target);
        // External proxy/IdP URLs are emitted as-is — the proxy strips
        // the existing session, re-prompts at the IdP, and proxies back
        // to the original target. No context-path mangling needed.
        return new RedirectView(redirectUrl);
    }

    /**
     * Phase E.5 follow-up (2026-06-03): emit {@code Location} headers that
     * include the servlet context path ({@code /LibreClinica} in dev
     * compose + production) so clients that follow redirects (browsers,
     * {@code curl --location}, the CI smoke probe) land on the right
     * Tomcat-context URL.
     *
     * <p>{@link RedirectView}'s default {@code contextRelative=false} sent
     * {@code Location: /pages/login/login}, which a context-strip-aware
     * client follows to {@code http://host/pages/login/login} — outside
     * the LibreClinica context, where Tomcat returns 404. Setting
     * {@code contextRelative=true} prepends the context so the Location
     * is {@code /LibreClinica/pages/login/login} and the follow-redirect
     * lands on the legacy login JSP as intended. This is why the CI
     * smoke probe's {@code curl --location ... /pages/sso/reauth} was
     * 404'ing (curl saw the 302, followed it outside the context,
     * landed on Tomcat's NoServletMappingFoundException 404). Direct
     * HEAD probes (no follow) reported the 302 correctly so the bug
     * stayed hidden in local manual testing.
     */
    private static RedirectView contextRelativeRedirect(String path) {
        RedirectView v = new RedirectView(path);
        v.setContextRelative(true);
        return v;
    }
}
