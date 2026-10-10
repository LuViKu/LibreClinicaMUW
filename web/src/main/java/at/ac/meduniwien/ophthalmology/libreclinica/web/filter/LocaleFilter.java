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
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.jstl.core.Config;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter for applying the OpenClinica's Locale
 *
 * @since Jan. 2012
 */
// @author ywang
@SuppressWarnings("all")
public final class LocaleFilter implements Filter {
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());

    /**
     * Save customized Locale into session for Locale attribute and fmt Locale; and set response Locale
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Tomcat 11 reports "Accept-Language: *" as Locale.ROOT. Spring's RequestContextFilter
        // (LocaleContextHolder) and Spring Security's message lookups then ask for a ROOT
        // bundle and fail with a 500 before any controller runs. Hand every downstream
        // consumer a request that never reports a language-less locale.
        HttpServletRequest req = new WildcardLocaleRequest((HttpServletRequest)request);
        HttpServletResponse resp = (HttpServletResponse)response;
        updateLocale(req,resp,LocaleResolver.resolveLocale(req));
        if (chain != null)  {
          chain.doFilter(req, response);
        }
    }

    /** Request whose getLocale()/getLocales() never yield a wildcard (language-less) locale. */
    static final class WildcardLocaleRequest extends HttpServletRequestWrapper {
        WildcardLocaleRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Locale getLocale() {
            return LocaleResolver.usableLocales((HttpServletRequest) getRequest()).get(0);
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return Collections.enumeration(
                    LocaleResolver.usableLocales((HttpServletRequest) getRequest()));
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    private void updateLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        if(locale != null) {
            HttpSession session = request.getSession(false);
            if(session != null) {
                session.setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
                Config.set(session, Config.FMT_LOCALE, locale);
                if(response != null)    response.setLocale(locale);
            } else {
                logger.debug("Locale can not be saved into session because session is null.");
            }
        } else {
            logger.debug("No Locale updating has been done because passed Locale is null.");
        }
    }
}
