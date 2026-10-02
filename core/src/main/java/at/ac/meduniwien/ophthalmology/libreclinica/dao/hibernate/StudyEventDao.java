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
import org.springframework.lang.NonNull;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings("resource") // Session comes from the JPA EntityManager (getCurrentSession); the transaction manager closes it
public class StudyEventDao extends AbstractDomainDao<StudyEvent> implements ApplicationEventPublisherAware{

	private ApplicationEventPublisher eventPublisher;

	@Override
	public Class<StudyEvent> domainClass(){
		return StudyEvent.class;
	}

	public StudyEvent fetchByStudyEventDefOID(String oid,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		 Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
         q.setParameter("studySubjectId", studySubjectId);
         q.setParameter("oid", oid);

         return q.getSingleResultOrNull();
	}

	@Transactional
	public StudyEvent fetchByStudyEventDefOIDAndOrdinal(String oid,Integer ordinal,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid and se.sampleOrdinal = :ordinal order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		 Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
         q.setParameter("studySubjectId", studySubjectId);
         q.setParameter("oid", oid);
         q.setParameter("ordinal", ordinal);
         return q.getSingleResultOrNull();
	}

    @Transactional(propagation = Propagation.NEVER)
    public StudyEvent fetchByStudyEventDefOIDAndOrdinalTransactional(String oid,Integer ordinal,Integer studySubjectId){
        String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid and se.sampleOrdinal = :ordinal order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
        Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studySubjectId", studySubjectId);
        q.setParameter("oid", oid);
        q.setParameter("ordinal", ordinal);
        return q.getSingleResultOrNull();
    }

	public Integer findMaxOrdinalByStudySubjectStudyEventDefinition(int studySubjectId, int studyEventDefinitionId) {
        String query = "select max(sample_ordinal) from study_event where study_subject_id = " + studySubjectId + " and study_event_definition_id = " + studyEventDefinitionId;
        NativeQuery<Integer> q = getCurrentSession().createNativeQuery(query, Integer.class);
        Integer result = q.getSingleResultOrNull();
        if (result == null) return 0;
        else return result.intValue();
    }

    @Transactional
	public List<StudyEvent> fetchListByStudyEventDefOID(String oid,Integer studySubjectId){
		String query = " from StudyEvent se where se.studySubject.studySubjectId = :studySubjectId and se.studyEventDefinition.oc_oid = :oid order by se.studyEventDefinition.ordinal,se.sampleOrdinal";
		Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studySubjectId", studySubjectId);
        q.setParameter("oid", oid);

        return q.getResultList();
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
			@NonNull ApplicationEventPublisher applicationEventPublisher) {
 this.eventPublisher = applicationEventPublisher;
	}

	@Transactional
    public StudyEvent findByStudyEventId(int studyEventId) {
        String query = "from " + getDomainClassName() + " study_event  where study_event.studyEventId = :studyeventid ";
        Query<StudyEvent> q = getCurrentSession().createQuery(query, StudyEvent.class);
        q.setParameter("studyeventid", studyEventId);
        return q.getSingleResultOrNull();
    }
}
