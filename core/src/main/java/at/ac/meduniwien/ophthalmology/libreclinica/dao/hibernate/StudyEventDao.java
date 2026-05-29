/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyEvent;
import at.ac.meduniwien.ophthalmology.libreclinica.patterns.ocobserver.OnStudyEventUpdated;
import at.ac.meduniwien.ophthalmology.libreclinica.patterns.ocobserver.StudyEventContainer;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class StudyEventDao extends AbstractDomainDao<StudyEvent> implements ApplicationEventPublisherAware{

	private ApplicationEventPublisher eventPublisher;

	public Class<StudyEvent> domainClass(){
		return StudyEvent.class;
	}

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation") 
	public StudyEvent fetchByStudyEventDefOID(String oid,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		 Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
         q.setParameter("studySubjectId", studySubjectId);
         q.setParameter("oid", oid);

         return q.uniqueResult();
	}

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
	@Transactional
	public StudyEvent fetchByStudyEventDefOIDAndOrdinal(String oid,Integer ordinal,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid and se.sampleOrdinal = :ordinal order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		 Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
         q.setParameter("studySubjectId", studySubjectId);
         q.setParameter("oid", oid);
         q.setParameter("ordinal", ordinal);
         return q.uniqueResult();
	}

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    @Transactional(propagation = Propagation.NEVER)
    public StudyEvent fetchByStudyEventDefOIDAndOrdinalTransactional(String oid,Integer ordinal,Integer studySubjectId){
        String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid and se.sampleOrdinal = :ordinal order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
        Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studySubjectId", studySubjectId);
        q.setParameter("oid", oid);
        q.setParameter("ordinal", ordinal);
        return q.uniqueResult();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("rawtypes")
	public Integer findMaxOrdinalByStudySubjectStudyEventDefinition(int studySubjectId, int studyEventDefinitionId) {
        String query = "select max(sample_ordinal) from study_event where study_subject_id = " + studySubjectId + " and study_event_definition_id = " + studyEventDefinitionId;
        NativeQuery q = getCurrentSession().createNativeQuery(query);
        Number result = (Number) q.uniqueResult();
        if (result == null) return 0;
        else return result.intValue();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    @Transactional
	public List<StudyEvent> fetchListByStudyEventDefOID(String oid,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studySubjectId", studySubjectId);
        q.setParameter("oid", oid);

        return q.list();
	}

	@Transactional
    public StudyEvent saveOrUpdate(StudyEventContainer container) {
        StudyEvent event = saveOrUpdate(container.getEvent());
        this.eventPublisher.publishEvent(new OnStudyEventUpdated(container));
        return event;
    }

   public StudyEvent saveOrUpdateTransactional(StudyEventContainer container) {
        StudyEvent event = saveOrUpdate(container.getEvent());
        this.eventPublisher.publishEvent(new OnStudyEventUpdated(container));
        return event;
    }

	@Override
	public void setApplicationEventPublisher(
			ApplicationEventPublisher applicationEventPublisher) {
 this.eventPublisher = applicationEventPublisher;
	}

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
	@Transactional
    public StudyEvent findByStudyEventId(int studyEventId) {
        String query = "from " + getDomainClassName() + " study_event  where study_event.studyEventId = :studyeventid ";
        Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studyeventid", studyEventId);
        return q.uniqueResult();
    }
}
