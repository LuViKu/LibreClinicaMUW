/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import java.util.ArrayList;
import java.util.Date;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.rule.RuleDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * @author Krikor Krumlian
 *
 * 2026-06-28 — heritage-debt audit (PR #262): the rule-evaluation surface in this
 * class is a known dead stub. {@link #initializeRule(RuleBean)} short-circuits
 * source/target evaluation to {@code true}, so the discrepancy-note arm never
 * fires. Live rule evaluation runs through the Spring-managed
 * {@code at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetService}
 * /{@code RuleActionRunLogService} pipeline instead; this bean is kept only
 * because {@link #runRule(int)} is still referenced from a handful of legacy
 * call sites and removing it is out of scope for the P0 error-handling sweep.
 *
 * To make the method functional one would need to:
 *   1. Resolve {@code sourceItemDataBean} / {@code targetItemDataBean} from
 *      the rule's source/target expressions against the supplied event CRF.
 *   2. Look up the corresponding {@code ItemFormMetadataBean} per side.
 *   3. Reinstate the original {@code fireRule(itemData, value, metadata, operator)}
 *      calls (commented out below) — they live on the heritage RuleDAO.
 *
 * Tracked in the B.5 follow-up backlog (see PR #262 description).
 */

public class RuleExecutionBusinessObject {

    private final SessionManager sm;
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    protected StudyBean currentStudy;
    protected UserAccountBean ub;

    public RuleExecutionBusinessObject(SessionManager sm, StudyBean currentStudy, UserAccountBean ub) {
        this.sm = sm;
        this.currentStudy = currentStudy;
        this.ub = ub;
    }

    /**
     * @deprecated 2026-06-28 — heritage-debt audit (PR #262): dead stub — see class Javadoc.
     */
    @Deprecated
    public void runRule(int eventCrfId) {
        // int eventCrfId = 11;
        EventCRFBean eventCrfBean = getEventCRFBean(eventCrfId);
        RuleSetBean ruleSetBean = getRuleSetBean(eventCrfBean);
        ArrayList<RuleBean> rules = getRuleBeans(ruleSetBean);
        for (RuleBean rule : rules) {
            initializeRule(rule);
        }
    }

    /**
     * @deprecated 2026-06-28 — heritage-debt audit (PR #262): the rule-eval body
     *     short-circuits to {@code sourceResult = true; targetResult = true;},
     *     so this method has no effect today. See the class Javadoc for the
     *     wiring needed to make it functional.
     */
    @Deprecated
    public void initializeRule(RuleBean rule) {
        // source data
        // ItemDataBean sourceItemDataBean = rule.getSourceItemDataBean();
        ItemDataBean sourceItemDataBean = null;

        // target data
        // ItemDataBean targetItemDataBean = rule.getTargetItemDataBean();
        ItemDataBean targetItemDataBean = null;

        // fireRules on source & target
        // 2026-06-28 — heritage-debt audit (PR #262): preserved heritage no-op
        // (replaces the original "KK FIX HERE" marker). The required wiring is
        // documented in the class and method Javadoc above. Filling this in is
        // deferred to B.5 follow-up.
        boolean sourceResult = true;// fireRule(sourceItemDataBean,rule.getSourceItemValue(),sourceItemFormMetadataBean,rule.getSourceOperator());
        boolean targetResult = true;// fireRule(targetItemDataBean,rule.getTargetItemValue(),targetItemFormMetadataBean,rule.getTargetOperator());

        if (sourceResult && targetResult) {
            // We are good
        }
        if (sourceResult == true && targetResult == false) {
            // file a descrepancy Note
            createDiscrepancyNote(rule.toString(), targetItemDataBean, sourceItemDataBean);
        }

    }

    private void createDiscrepancyNote(String description, ItemDataBean targetItemDataBean, ItemDataBean sourceItemDataBean) {

        DiscrepancyNoteBean note = new DiscrepancyNoteBean();
        note.setDescription(description);
        note.setDetailedNotes("");
        note.setOwner(ub);
        note.setCreatedDate(new Date());
        note.setResolutionStatusId(1);
        note.setDiscrepancyNoteTypeId(1);
        // note.setParentDnId(parentId);
        // note.setField(field);
        note.setEntityId(targetItemDataBean.getId());
        note.setEntityType(DiscrepancyNoteBean.ITEM_DATA);
        note.setColumn("value");
        note.setStudyId(currentStudy.getId());

        DiscrepancyNoteDAO discrepancyNoteDao = new DiscrepancyNoteDAO(sm.getDataSource());
        note = (DiscrepancyNoteBean) discrepancyNoteDao.create(note);
        discrepancyNoteDao.createMapping(note);

    }

    // These are dao mostly calls see how to reduce redundancy
    private EventCRFBean getEventCRFBean(int eventCrfBeanId) {
        EventCRFDAO eventCrfDao = new EventCRFDAO(sm.getDataSource());
        return eventCrfBeanId > 0 ? (EventCRFBean) eventCrfDao.findByPK(eventCrfBeanId) : null;
    }

    private RuleSetBean getRuleSetBean(EventCRFBean eventCrfBean) {
        // RuleSetDAO ruleSetDao = new RuleSetDAO(sm.getDataSource());
        return null;
    }

    private ArrayList<RuleBean> getRuleBeans(RuleSetBean ruleSet) {
        RuleDAO ruleDao = new RuleDAO(sm.getDataSource());
        return ruleSet != null ? ruleDao.findByRuleSet(ruleSet) : new ArrayList<RuleBean>();
    }

}
