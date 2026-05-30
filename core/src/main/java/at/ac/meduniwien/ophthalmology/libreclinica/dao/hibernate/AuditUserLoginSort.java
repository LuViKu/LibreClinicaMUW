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
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds ORDER BY clauses for the audit-user-login table.
 *
 * <p>Phase B.5: migrated from Hibernate 5's
 * {@code org.hibernate.criterion.Order} to {@code jakarta.persistence.criteria.Order}.
 */
public class AuditUserLoginSort implements CriteriaCommand<AuditUserLoginBean> {

    private final List<Sort> sorts = new ArrayList<>();

    public void addSort(String property, String order) {
        sorts.add(new Sort(property, order));
    }

    public List<Sort> getSorts() {
        return sorts;
    }

    @Override
    public void apply(CriteriaBuilder cb, CriteriaQuery<?> query, Root<AuditUserLoginBean> root) {
        List<Order> orders = new ArrayList<>();
        for (Sort sort : sorts) {
            if (Sort.ASC.equalsIgnoreCase(sort.getOrder())) {
                orders.add(cb.asc(root.get(sort.getProperty())));
            } else if (Sort.DESC.equalsIgnoreCase(sort.getOrder())) {
                orders.add(cb.desc(root.get(sort.getProperty())));
            }
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
    }

    public static class Sort {
        public final static String ASC = "asc";
        public final static String DESC = "desc";

        private final String property;
        private final String order;

        public Sort(String property, String order) {
            this.property = property;
            this.order = order;
        }

        public String getProperty() { return property; }
        public String getOrder()    { return order;    }
    }
}
