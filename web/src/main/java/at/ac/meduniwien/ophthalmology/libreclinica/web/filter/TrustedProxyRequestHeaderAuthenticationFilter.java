/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Phase D.3 (DR-014): {@link RequestHeaderAuthenticationFilter}
 * subclass that enforces a CIDR allowlist on the request remote
 * address before honouring the SSO pre-auth header.
 *
 * <p>The network-layer trust assumption is load-bearing: only the
 * reverse-proxy sidecar (Apache+mod_shib in the reference deployment;
 * any header-injecting reverse proxy in other deployments) may inject
 * the principal header. If Tomcat is reachable directly — outside the
 * compose-internal network or accidentally bound to a public interface —
 * a malicious client could supply {@code REMOTE_USER: root} and gain
 * admin. The allowlist refuses such requests by returning {@code null}
 * from {@link #getPreAuthenticatedPrincipal}, which makes the filter
 * no-op and the request falls through to local username/password auth.
 *
 * <p>The allowlist comes from
 * {@code libreclinica.sso.trusted-proxy.allowed-cidrs}. Default in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties.TrustedProxy}
 * = loopback + compose-internal RFC1918 (172.16/12) for the
 * reference compose deployment. Production must narrow to the
 * specific Apache sidecar IP and document that Tomcat binds only
 * to the compose-internal network.
 *
 * @see <a href="../../../../../../../docs/development/modernization/decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication">DR-014</a>
 */
public class TrustedProxyRequestHeaderAuthenticationFilter
        extends RequestHeaderAuthenticationFilter {

    private static final Logger log = LoggerFactory
            .getLogger(TrustedProxyRequestHeaderAuthenticationFilter.class);

    private final List<IpAddressMatcher> trustedMatchers;
    private String principalHeaderName = "REMOTE_USER";

    public TrustedProxyRequestHeaderAuthenticationFilter(List<String> allowedCidrs) {
        this.trustedMatchers = new ArrayList<>(allowedCidrs.size());
        for (String cidr : allowedCidrs) {
            this.trustedMatchers.add(new IpAddressMatcher(cidr));
        }
    }

    /**
     * Overrides Spring Security's setter so we can also cache the
     * header name (the parent class field is private with no getter,
     * so we keep our own copy for the untrusted-upstream log line).
     */
    @Override
    public void setPrincipalRequestHeader(String principalRequestHeader) {
        super.setPrincipalRequestHeader(principalRequestHeader);
        this.principalHeaderName = principalRequestHeader;
    }

    @Override
    protected Object getPreAuthenticatedPrincipal(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        boolean trusted = false;
        for (IpAddressMatcher matcher : trustedMatchers) {
            if (matcher.matches(remoteAddr)) {
                trusted = true;
                break;
            }
        }
        if (!trusted) {
            // Untrusted upstream: refuse to honour any pre-auth header
            // claim. Log at INFO so operators can see header-spoofing
            // attempts surface during deployment debugging; if this
            // becomes noisy in production, downgrade to DEBUG.
            if (request.getHeader(principalHeaderName) != null) {
                log.info("Refused SSO pre-auth from untrusted remote {} —"
                        + " header '{}' present but CIDR allowlist does"
                        + " not include this address",
                        remoteAddr, principalHeaderName);
            }
            return null;
        }
        return super.getPreAuthenticatedPrincipal(request);
    }
}
