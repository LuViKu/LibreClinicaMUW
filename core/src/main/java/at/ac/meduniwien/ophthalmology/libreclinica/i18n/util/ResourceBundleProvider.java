/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.i18n.util;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class ResourceBundleProvider {
    /**
     * The locale bound to the calling thread. It was a plain HashMap keyed by Thread,
     * written by every request thread - unsafe (lost entries, resize loops).
     * Every access was to the current thread's entry, so a ThreadLocal is equivalent.
     *
     * @author Nacho M. Castejon and Jose Martinez Garcia, BAP Health
     */
    private static final ThreadLocal<Locale> CURRENT_LOCALE = new ThreadLocal<Locale>();
    /**
     * Contains the set of ResourceBundles associated to each locale.
     */
    static final ConcurrentHashMap<Locale, HashMap<String, ResourceBundle>> resBundleSetMap = new ConcurrentHashMap<Locale, HashMap<String, ResourceBundle>>();

    public static void updateLocale(Locale l) {
        //logger.info("* found locale " + l.getDisplayCountry() + " " + l.getDisplayLanguage());
        if (l == null) {
            clearLocale();
            return;
        }
        CURRENT_LOCALE.set(l);
        bundlesFor(l);
    }

    /** The bundle names {@link #bundlesFor} loads for each locale. */
    private static final String[] BUNDLE_NAMES = {
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.admin",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.audit_events",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.format",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.terms",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.workflow",
        "at.ac.meduniwien.ophthalmology.libreclinica.i18n.licensing",
    };

    /**
     * The bundles of a locale, loaded the first time the locale is asked for.
     * They used to be loaded only by {@link #updateLocale}, so asking for a
     * locale no request had set yet (as {@code POST /me/password} does with
     * {@code Locale.ENGLISH} on a German-speaking installation) failed with a
     * NullPointerException.
     */
    private static HashMap<String, ResourceBundle> bundlesFor(Locale l) {
        return resBundleSetMap.computeIfAbsent(l, locale -> {
            HashMap<String, ResourceBundle> resBundleSet = new HashMap<String, ResourceBundle>();
            for (String name : BUNDLE_NAMES) {
                resBundleSet.put(name, ResourceBundle.getBundle(name, locale));
            }
            return resBundleSet;
        });
    }

    public static Locale getLocale() {
        return CURRENT_LOCALE.get();
    }

    /** Unbinds the calling thread's locale; use on pooled worker threads once the work is done. */
    public static void clearLocale() {
        CURRENT_LOCALE.remove();
    }

    public static ResourceBundle getAdminBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.admin");
    }

    public static ResourceBundle getAdminBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.admin", locale);
    }

    public static ResourceBundle getAuditEventsBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.audit_events");
    }

    public static ResourceBundle getAuditEventsBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.audit_events", locale);
    }

    public static ResourceBundle getExceptionsBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions");
    }

    public static ResourceBundle getExceptionsBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions", locale);
    }

    public static ResourceBundle getFormatBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.format");
    }

    public static ResourceBundle getFormatBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.format", locale);
    }

    public static ResourceBundle getPageMessagesBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages");
    }

    public static ResourceBundle getPageMessagesBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages", locale);
    }

    public static ResourceBundle getTermsBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.terms");
    }

    public static ResourceBundle getTermsBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.terms", locale);
    }

    public static ResourceBundle getWordsBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words");
    }

    public static ResourceBundle getWordsBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words", locale);
    }

    public static ResourceBundle getTextsBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes", locale);
    }

    public static ResourceBundle getTextsBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes");
    }

    public static ResourceBundle getWorkflowBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.workflow", locale);
    }

    public static ResourceBundle getLicensingBundle() {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.licensing");
    }

    public static ResourceBundle getLicensingBundle(Locale locale) {
        return getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.licensing", locale);
    }

    /**
     * Returns the required bundle, using the current thread to determine the
     * appropiate locale.
     *
     * @param name
     *            requested bundle name.
     * @return
     */
    private static ResourceBundle getResBundle(String name) {

        return resBundleSetMap.get(CURRENT_LOCALE.get()).get(name);
    }

    /**
     *
     * @param name
     *            Required bundle name
     * @param locale
     *            Required locale
     * @return The corresponding ResourceBundle
     */
    private static ResourceBundle getResBundle(String name, Locale locale) {
        return bundlesFor(locale).get(name);
    }

    /**
     *
     * @param key
     * @return If found, the value associated with the key in the Admin
     *         ResourceBundle else, the key.
     */
    public static String getResAdmin(String key) {
        String value;
        try {
            value = getAdminBundle().getString(key);
        } catch (MissingResourceException mre) {
            value = key;
        }
        return value;
    }

    /**
     *
     * @param key
     * @return If found, the value associated with the key in the Term
     *         ResourceBundle else, the key.
     */
    public static String getResTerm(String key) {
        String value;
        try {
            value = getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.terms").getString(key);
        } catch (MissingResourceException mre) {
            value = key;
        }
        return value;
    }

    public static String getResWord(String key) {
        String value;
        try {
            value = getResBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words").getString(key);
        } catch (MissingResourceException mre) {
            value = key;
        }
        return value;
    }

}
