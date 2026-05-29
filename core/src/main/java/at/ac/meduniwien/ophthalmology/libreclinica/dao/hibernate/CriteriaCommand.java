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

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * Filter or sort command applied to a JPA {@link CriteriaQuery}.
 *
 * <p>Phase B.5: ported from Hibernate 5's {@code org.hibernate.Criteria}
 * (deleted in Hibernate 6) to the {@code jakarta.persistence.criteria} API.
 * Implementations mutate the supplied query — adding {@code where} predicates
 * (filters) or {@code orderBy} clauses (sorts) using the supplied
 * {@link CriteriaBuilder} and {@link Root}.
 */
public interface CriteriaCommand<T> {

    void apply(CriteriaBuilder cb, CriteriaQuery<?> query, Root<T> root);
}
