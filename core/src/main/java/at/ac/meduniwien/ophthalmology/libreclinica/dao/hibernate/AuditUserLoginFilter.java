/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Builds WHERE-clause predicates for the audit-user-login table.
 *
 * <p>Phase B.5: migrated from Hibernate 5's
 * {@code org.hibernate.criterion.Restrictions} to {@code jakarta.persistence.criteria}.
 * The {@code loginAttemptDate} filter does prefix-on-yyyy[-MM[-dd[ HH[:mm]]]]
 * matching by parsing each partial date format and adding a {@code between}
 * over the implied [startDate, startDate+granularity) window — the original
 * Hibernate 5 form did the same, just expressed via {@code Restrictions.between}.
 */
public class AuditUserLoginFilter implements CriteriaCommand<AuditUserLoginBean> {

    private final List<Filter> filters = new ArrayList<>();

    public void addFilter(String property, Object value) {
        filters.add(new Filter(property, value));
    }

    @Override
    public void apply(CriteriaBuilder cb, CriteriaQuery<?> query, Root<AuditUserLoginBean> root) {
        List<Predicate> predicates = new ArrayList<>();
        for (Filter filter : filters) {
            collect(cb, root, filter.getProperty(), filter.getValue(), predicates);
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(new Predicate[0])));
        }
    }

    private void collect(CriteriaBuilder cb, Root<AuditUserLoginBean> root,
                         String property, Object value, List<Predicate> predicates) {
        if (value == null) {
            return;
        }
        if ("loginStatus".equals(property)) {
            predicates.add(cb.equal(root.get(property), LoginStatus.getByName((String) value)));
        } else if ("loginAttemptDate".equals(property)) {
            // Try every supported granularity in turn; the original code added
            // ALL of them and let Hibernate 5 sort it out. JPA Criteria requires
            // explicit choice, so add ALL predicates here too (they OR-out:
            // only one will match a given row).
            Predicate dateGroup = orDate(cb, root, String.valueOf(value));
            if (dateGroup != null) {
                predicates.add(dateGroup);
            }
        } else {
            predicates.add(cb.like(cb.lower(root.get(property).as(String.class)),
                    "%" + String.valueOf(value).toLowerCase() + "%"));
        }
    }

    private Predicate orDate(CriteriaBuilder cb, Root<AuditUserLoginBean> root, String value) {
        List<Predicate> dateRanges = new ArrayList<>();
        addRangeIfMatch(cb, root, "yyyy-MM-dd HH:mm", value, ChronoStep.MINUTE, dateRanges);
        addRangeIfMatch(cb, root, "yyyy-MM-dd HH",    value, ChronoStep.HOUR,   dateRanges);
        addRangeIfMatch(cb, root, "yyyy-MM-dd",       value, ChronoStep.DAY,    dateRanges);
        addRangeIfMatch(cb, root, "yyyy-MM",          value, ChronoStep.MONTH,  dateRanges);
        addRangeIfMatch(cb, root, "yyyy",             value, ChronoStep.YEAR,   dateRanges);
        if (dateRanges.isEmpty()) {
            return null;
        }
        return cb.or(dateRanges.toArray(new Predicate[0]));
    }

    private void addRangeIfMatch(CriteriaBuilder cb, Root<AuditUserLoginBean> root,
                                 String pattern, String value, ChronoStep step,
                                 List<Predicate> out) {
        try {
            DateFormat format = new SimpleDateFormat(pattern);
            Date startDate = format.parse(value);
            ZonedDateTime dt = startDate.toInstant().atZone(ZoneId.systemDefault());
            switch (step) {
                case MINUTE: dt = dt.plusMinutes(1); break;
                case HOUR:   dt = dt.plusHours(1);   break;
                case DAY:    dt = dt.plusDays(1);    break;
                case MONTH:  dt = dt.plusMonths(1);  break;
                case YEAR:   dt = dt.plusYears(1);   break;
            }
            Date endDate = Date.from(dt.toInstant());
            out.add(cb.between(root.<Date>get("loginAttemptDate"), startDate, endDate));
        } catch (Exception e) {
            // Pattern doesn't match — try the next narrower form.
        }
    }

    private enum ChronoStep { MINUTE, HOUR, DAY, MONTH, YEAR }

    private static class Filter {
        private final String property;
        private final Object value;

        public Filter(String property, Object value) {
            this.property = property;
            this.value = value;
        }

        public String getProperty() { return property; }
        public Object getValue()    { return value;    }
    }
}
