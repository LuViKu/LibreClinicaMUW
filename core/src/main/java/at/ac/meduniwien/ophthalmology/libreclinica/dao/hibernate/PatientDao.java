/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.Patient;
import org.hibernate.query.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * C4 (2026-06-20) — Hibernate DAO for the hard {@code patient} table.
 *
 * <p>The link-patient endpoint (and any future patient-CRUD code) goes
 * through here to resolve a {@code patient_uuid} into a managed
 * {@link Patient} or to persist a freshly-minted one. The DAO sits on
 * top of {@link AbstractDomainDao} so it picks up {@code findById},
 * {@code save}, {@code count}, etc. for free.
 */
public class PatientDao extends AbstractDomainDao<Patient> {

    @Override
    Class<Patient> domainClass() {
        return Patient.class;
    }

    /**
     * Resolve the patient row that owns the supplied
     * {@code patient_uuid}. Returns {@code null} when no such row
     * exists — callers that mint a fresh UUID + insert should follow up
     * with a {@link #save(Patient)}.
     *
     * @param uuid non-null patient_uuid
     * @return the managed {@link Patient} or {@code null}
     */
    @Transactional
    public Patient findByUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        String hql = "from " + getDomainClassName()
                + " p where p.patientUuid = :uuid";
        Query<Patient> q = getCurrentSession().createQuery(hql, Patient.class);
        q.setParameter("uuid", uuid);
        return q.getSingleResultOrNull();
    }
}
