/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy;

/**
 * @author jxu Intervention object
 */
@SuppressWarnings("all")
public class InterventionBean {
    private int id;
    private String name;
    private String type;
    private static int count = 0;

    public InterventionBean(String type, String name) {
        setName(name);
        setType(type);
        setId();
    }

    /**
     * @return Returns the name.
     */
    public int getId() {
        return id;
    }

    public void setId() {
        this.id = count++;

    }

    /**
     * @return Returns the name.
     */
    public String getName() {
        return name;
    }

    /**
     * @param name
     *            The name to set.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return Returns the type.
     */
    public String getType() {
        return type;
    }

    /**
     * @param type
     *            The type to set.
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * The same intervention: type and name equal ignoring case.
     *
     * <p>Until 2026-09 this was {@code equals(InterventionBean)}, an overload
     * nothing called, next to a {@code hashCode} that returned the
     * per-instance counter, so the two could not agree. The study servlets
     * only add interventions to lists and print them.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof InterventionBean)) {
            return false;
        }
        InterventionBean other = (InterventionBean) obj;
        return equalsIgnoreCase(type, other.type) && equalsIgnoreCase(name, other.name);
    }

    /** Consistent with {@link #equals(Object)}: hashes the case-folded type and name. */
    @Override
    public int hashCode() {
        return 31 * caseFoldedHash(type) + caseFoldedHash(name);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /**
     * A hash that agrees with {@link String#equalsIgnoreCase}: it folds each
     * code point the way that method compares them (upper case, then lower
     * case), which toLowerCase() on the whole string does not do for every
     * character.
     */
    private static int caseFoldedHash(String s) {
        if (s == null) {
            return 0;
        }
        return s.codePoints().map(c -> Character.toLowerCase(Character.toUpperCase(c))).reduce(17, (h, c) -> 31 * h + c);
    }

    @Override
    public String toString() {
        return getType() + "/" + getName();

    }
}
