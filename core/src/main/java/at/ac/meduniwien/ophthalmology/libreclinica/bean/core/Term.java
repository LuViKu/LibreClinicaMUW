/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.core;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Superclass for controlled vocabulary terms like status, role, etc.
 *
 * @author ssachs
 */
@SuppressWarnings("all")
public class Term extends EntityBean {

	private static final long serialVersionUID = 4380127915595883173L;
	//Locale locale;
    ResourceBundle resterm;
    protected String description;

    public Term() {
        super();
    }

    public Term(int id, String name) {
        this(id, name, "");
    }

    public Term(int id, String name, String description) {
        // Direct field assignment to avoid this-escape warnings (javac since Java 21):
        // calling overridable setters from a constructor lets a subclass observe a
        // partially-constructed Term. EntityBean#name and Term#description are plain
        // fields; EntityBean#setId additionally flips `active` when id > 0, so we
        // replicate that here rather than calling the setter.
        this.id = id;
        if (id > 0) {
            this.active = true;
        }
        this.name = name;
        this.description = description;
    }

    public static Term get(int id, List<Term> list) {
        Term t = new Term(id, "");

        for (Term temp : list) {
            if (temp.equals(t)) {
                return temp;
            }
        }

        return new Term();
    }

    public static boolean contains(int id, List<? extends Term> list) {
        return list.stream().anyMatch(t -> new Term(id, "").equals(t));
    }

    @Override
    public String getName() {
        resterm = ResourceBundleProvider.getTermsBundle();
        return resterm.getString(this.name).trim();
    }

    // NOTE: localised name resolve
    /*
     * public String getLocalizedName() {
     * locale = LocaleProvider.getLocale();
     * resterm = ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.terms", locale);
     * return resterm.getString(this.name);
     * }
     */

    /**
     * @return Returns the description.
     */
    public String getDescription() {
        if (!this.description.isEmpty()) {
            resterm = ResourceBundleProvider.getTermsBundle();
            return resterm.getString(this.description).trim();
        } else {
            return null;
        }
    }

    /**
     * @param description The description to set.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    public boolean equals(Term t) {
        return id == t.id;
    }

    @Override
    public int hashCode() {
        return id;
    }
    
}
