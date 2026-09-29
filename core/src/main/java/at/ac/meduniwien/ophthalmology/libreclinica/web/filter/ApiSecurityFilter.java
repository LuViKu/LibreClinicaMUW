/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.io.IOException;
import java.util.StringTokenizer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Created by krikorkrumlian on 8/7/15.
 */
@SuppressWarnings("all")
public class ApiSecurityFilter extends OncePerRequestFilter {

    private String realm = "Protected";

    @Autowired
    private DataSource dataSource;


    /**
     * Admits a request to {@code /pages/auth/api/*} only with HTTP Basic
     * credentials whose user part is a known API key; everything else is
     * answered 401 and goes no further.
     *
     * <p>The heritage filter sent the 401 but then continued the chain when no
     * Authorization header was present, so the controller still ran (and
     * performed its writes) behind the error page, and it skipped the check
     * entirely for any scheme other than Basic. Together with
     * {@code /pages/auth/api/**} being permitAll in SecurityConfig, that left
     * these endpoints guarded only by whatever each controller checked
     * (code-scanning triage 2026-09-29).
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String apiKey = basicUser(request.getHeader("Authorization"));
        if (apiKey == null) {
            unauthorized(response);
            return;
        }
        UserAccountBean ub = new UserAccountDAO(dataSource).findByApiKey(apiKey);
        if (ub == null || ub.getId() == 0 || !ub.isActive() || refused(ub.getStatus())) {
            unauthorized(response, "Bad credentials");
            return;
        }
        request.getSession().setAttribute("userBean", ub);
        filterChain.doFilter(request, response);
    }

    /** A removed or locked account's API key opens nothing. */
    static boolean refused(Status status) {
        return status != null && (status.isDeleted() || status.isLocked());
    }

    /**
     * The user part of an HTTP Basic {@code Authorization} header, or null when
     * the header is absent, uses another scheme, is not valid Base64, has no
     * colon, or names an empty user.
     */
    static String basicUser(String authHeader) {
        if (authHeader == null) return null;
        StringTokenizer st = new StringTokenizer(authHeader);
        if (!st.hasMoreTokens() || !"Basic".equalsIgnoreCase(st.nextToken()) || !st.hasMoreTokens()) return null;
        String token = st.nextToken();
        if (!Base64.isBase64(token)) return null;
        String credentials = new String(Base64.decodeBase64(token), java.nio.charset.StandardCharsets.UTF_8);
        int p = credentials.indexOf(':');
        if (p == -1) return null;
        String user = credentials.substring(0, p).trim();
        return user.isEmpty() ? null : user;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        response.sendError(401, message);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        unauthorized(response, "Unauthorized");
    }
}
