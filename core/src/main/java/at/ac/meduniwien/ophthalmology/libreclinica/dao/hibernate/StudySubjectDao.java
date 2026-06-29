/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.OidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.StudySubjectOidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.Study;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyEvent;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudySubject;
import org.hibernate.query.Query;

@SuppressWarnings("all")

public class StudySubjectDao extends AbstractDomainDao<StudySubject> {

    @Override
    Class<StudySubject> domainClass() {
        return StudySubject.class;
    }

    public List<StudySubject> findAllByStudy(Integer studyId) {
        String query = "from " + getDomainClassName() + " do where do.study.studyId = :studyid";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("studyid", studyId);
        return q.getResultList();
      
    }

    public StudySubject findByOcOID(String OCOID) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.ocOid = :OCOID";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("OCOID", OCOID);
        return q.getSingleResultOrNull();
    }

    public StudySubject findByLabelAndStudy(String embeddedStudySubjectId, Study study) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.study.studyId = :studyid and do.label = :label";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("studyid", study.getStudyId());
        q.setParameter("label", embeddedStudySubjectId);
        return q.getSingleResultOrNull();
    }

    public StudySubject findByLabelAndStudyOrParentStudy(String embeddedStudySubjectId, Study study) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where (do.study.studyId = :studyid or do.study.study.studyId = :studyid) and do.label = :label";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("studyid", study.getStudyId());
        q.setParameter("label", embeddedStudySubjectId);
        return q.getSingleResultOrNull();
    }

    public ArrayList<StudySubject> findByLabelAndParentStudy(String embeddedStudySubjectId, Study parentStudy) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.study.study.studyId = :studyid and do.label = :label";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("studyid", parentStudy.getStudyId());
        q.setParameter("label", embeddedStudySubjectId);
        return new ArrayList<>(q.getResultList());
    }

    /**
     * Cross-study label match: every {@link StudySubject} whose {@code label} exactly
     * matches, regardless of study membership. Auto-removed rows ({@code status_id = 7})
     * are excluded (they shadow legitimate re-enrolments); removed rows ({@code status_id = 5})
     * are NOT excluded so the dedup-preflight can surface them.
     */
    public List<StudySubject> findByLabelAcrossAllStudies(String label) {
        if (label == null || label.isBlank()) {
            return new ArrayList<>();
        }
        String query = "from " + getDomainClassName() + " do "
                + " where do.label = :label "
                + "   and do.status.id != 7";
        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("label", label);
        return q.getResultList();
    }

    /**
     * Cross-study PHI triplet match: every {@link StudySubject} whose owning {@code subject}
     * row has a case-insensitive first+last name match and an exact date-of-birth match.
     * Auto-removed rows ({@code status_id = 7}) are excluded (as in
     * {@link #findByLabelAcrossAllStudies(String)}). Mirrors the dedup index on
     * {@code subject(LOWER(first_name), LOWER(last_name), date_of_birth)}.
     */
    public List<StudySubject> findByPhiTripleAcrossAllStudies(String firstName,
                                                              String lastName,
                                                              java.time.LocalDate dob) {
        if (firstName == null || firstName.isBlank()
                || lastName == null || lastName.isBlank()
                || dob == null) {
            return new ArrayList<>();
        }
        // {@link Subject} doesn't map first_name / last_name as JPA
        // properties (the columns were added by the lc-muw-2026-06-08
        // PHI migration but the entity wasn't updated; that's the wider
        // legacy Hibernate-vs-raw-JDBC split the codebase carries). Use
        // a native query so we can still bind {@link StudySubject}
        // entities with the column predicate. Status filtering mirrors
        // the HQL siblings — status_id != 7 excludes auto-removed rows.
        String sql = "SELECT ss.* FROM study_subject ss "
                + "  JOIN subject s ON s.subject_id = ss.subject_id "
                + " WHERE LOWER(s.first_name) = LOWER(:firstName) "
                + "   AND LOWER(s.last_name)  = LOWER(:lastName) "
                + "   AND s.date_of_birth     = :dob "
                + "   AND ss.status_id       != 7";
        Query<StudySubject> q = getCurrentSession()
                .createNativeQuery(sql, StudySubject.class);
        q.setParameter("firstName", firstName);
        q.setParameter("lastName", lastName);
        q.setParameter("dob", java.sql.Date.valueOf(dob));
        return q.getResultList();
    }

    public ArrayList<StudyEvent> fetchListSEs(String id) {
        String query = " from StudyEvent se where se.studySubject.ocOid = :id order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
        Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("id", id.toString());

        return new ArrayList<>(q.getResultList());

    }
    public String getValidOid(StudySubject studySubject, ArrayList<String> oidList) {
    OidGenerator oidGenerator = new StudySubjectOidGenerator();
        String oid = getOid(studySubject);
        String oidPreRandomization = oid;
        while (findByOcOID(oid) != null || oidList.contains(oid)) {
            oid = oidGenerator.randomizeOid(oidPreRandomization);
        }
        return oid;
    }

    private String getOid(StudySubject studySubject) {
        OidGenerator oidGenerator = new StudySubjectOidGenerator();
        String oid;
        try {
            oid = studySubject.getOcOid() != null ? studySubject.getOcOid() : oidGenerator.generateOid(studySubject.getLabel());
            return oid;
        } catch (Exception e) {
            throw new RuntimeException("CANNOT GENERATE OID");
        }
    }

    public int findTheGreatestLabelByStudy(Integer studyId) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where (do.study.studyId = :studyid or do.study.study.studyId = :studyid)";

        Query<StudySubject> q = getCurrentSession().createQuery(query, StudySubject.class);
        q.setParameter("studyid", studyId);
        List<StudySubject> allStudySubjects = q.getResultList();
        
        int greatestLabel = 0;
        for (StudySubject subject:allStudySubjects) {
            int labelInt = 0;
            try {
                labelInt = Integer.parseInt(subject.getLabel());
            } catch (NumberFormatException ne) {
                labelInt = 0;
            }
            if (labelInt > greatestLabel) {
                greatestLabel = labelInt;
            }
        }
        return greatestLabel;
    }

}
