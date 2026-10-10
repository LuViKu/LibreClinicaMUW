/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.i18n.core;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Customize Locale.
 *
 * @since Jan. 2012
 */
// @author ywang
@SuppressWarnings("all")
public final class LocaleResolver {
	private final static Locale DEFAULT_LOCALE = Locale.ENGLISH;
	private final static String LOCALE_SESSION_ATTRIBUTE_NAME
	    = SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME;

	/**
	 * Find Locale basing on order of accept languages and availability of i18n properties files.
	 *
	 * @param request
	 * @return
	 */
	public final static Locale resolveLocale(HttpServletRequest request) {
		if(request != null) {
			for(
			Enumeration<Locale> locales = request.getLocales(); locales.hasMoreElements();) {
				Locale locale = locales.nextElement();
				// "Accept-Language: *" (sent by Node's fetch) is parsed by Tomcat 11 as
				// Locale.ROOT. It names no language: skip it, before it can reach
				// ResourceBundleProvider's thread-local, and try the next one.
				if(isWildcard(locale)) {
				    continue;
				}
				ResourceBundleProvider.updateLocale(locale);
				if(isQualifiedLocale(locale)) {
				    locale = ResourceBundleProvider.getFormatBundle(locale).getLocale();
                    return locale;
				} else if(getDefaultLocale().getLanguage().equalsIgnoreCase(locale.getLanguage())) {
				    break;
				}
			}
        }
		return getDefaultLocale();
	}

	/**
	 * True for a locale that names no language (Locale.ROOT, as produced by
	 * "Accept-Language: *"), or null.
	 */
	public final static boolean isWildcard(Locale locale) {
	    return locale == null || locale.getLanguage().isEmpty();
	}

	/**
	 * The request's accepted locales without wildcard entries, in preference
	 * order. Never empty: falls back to the default locale, as the Servlet
	 * specification does for a request without a usable Accept-Language.
	 */
	public final static List<Locale> usableLocales(HttpServletRequest request) {
	    List<Locale> usable = new ArrayList<>();
	    for(Enumeration<Locale> e = request.getLocales(); e != null && e.hasMoreElements();) {
	        Locale l = e.nextElement();
	        if(!isWildcard(l)) {
	            usable.add(l);
	        }
	    }
	    if(usable.isEmpty()) {
	        usable.add(getDefaultLocale());
	    }
	    return usable;
	}

	/**
	 * Get Locale from session first. If it is null, will call resolveLocale method.
	 * @param request
	 * @return
	 */
	public final static Locale getLocale(HttpServletRequest request) {
	    Locale locale = getLocaleInSession(request.getSession(false));
	    if(locale == null) {
	        return resolveLocale(request);
	    }
	    return locale;
	}

	/*
     * Null will be returned if HttpSession is null or no Locale attribute exists
     * @param session
     * @return
     */
    private final static Locale getLocaleInSession(HttpSession session) {
        if(session != null) {
            return (Locale)session.getAttribute(getLocaleSessionAttributeName());
        }
        return null;
    }

	/*
	 * A locale is qualified only if all ResourceBundles' Locales are available
	 */
	private static boolean isQualifiedLocale(Locale locale) {
		ResourceBundle rb = ResourceBundleProvider.getAdminBundle(locale);
		if(isAvailable(rb, locale)) {
			rb = ResourceBundleProvider.getAuditEventsBundle(locale);
			if(isAvailable(rb, locale))	{
				rb = ResourceBundleProvider.getExceptionsBundle(locale);
				if(isAvailable(rb, locale))	{
					rb = ResourceBundleProvider.getFormatBundle(locale);
					if(isAvailable(rb, locale))	{
						rb = ResourceBundleProvider.getPageMessagesBundle(locale);
						if(isAvailable(rb, locale)) {
							rb = ResourceBundleProvider.getTermsBundle(locale);
							if(isAvailable(rb, locale)) {
								rb = ResourceBundleProvider.getTextsBundle(locale);
								if(isAvailable(rb, locale)) {
									rb = ResourceBundleProvider.getWordsBundle(locale);
									if(isAvailable(rb, locale)) {
										rb = ResourceBundleProvider.getWorkflowBundle(locale);
										return isAvailable(rb, locale);
									}
								}
							}
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean isAvailable(ResourceBundle rb, Locale locale) {
	    if(rb != null ) {
	        Locale loc = rb.getLocale();
	        return loc != null && loc.toString().length()>0
	                && loc.getLanguage().equals(locale.getLanguage());
	    }
	    return false;
	}

	public final static String getLocaleSessionAttributeName() {
	    return LocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME;
	}

	public static Locale getDefaultLocale() {
	    return LocaleResolver.DEFAULT_LOCALE;
	}
}
