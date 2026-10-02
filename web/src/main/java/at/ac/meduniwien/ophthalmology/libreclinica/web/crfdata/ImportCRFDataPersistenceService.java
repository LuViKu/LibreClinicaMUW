/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.crfdata;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.DisplayItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.DisplayItemBeanWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ImportDataRuleRunnerContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.web.job.CrfBusinessLogicHelper;
import at.ac.meduniwien.ophthalmology.libreclinica.web.job.ImportSpringJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves validated CRF import data: the save step of the legacy import
 * ({@code VerifyImportedCRFDataServlet}, {@code action=save}), which
 * {@link ImportSpringJob} repeats for scheduled imports. The SPA commit
 * ({@code controller.api.ImportApiController}) runs it so that it writes what
 * those write:
 * <ul>
 *   <li>item data through {@link ItemDataDAO} (an existing value of an
 *       overwritable CRF is updated, anything else created), so the
 *       item_data triggers write the same audit rows;</li>
 *   <li>a Failed Validation Check discrepancy note, with its child, for each
 *       soft validation message ({@link ImportSpringJob#createDiscrepancyNote});</li>
 *   <li>each event CRF marked started or complete as the file's
 *       {@code EventCRFStatus} says ({@link CrfBusinessLogicHelper});</li>
 *   <li>source data verification reset on an event CRF whose values
 *       changed;</li>
 *   <li>the study's rules run on the imported data, dry run before the save
 *       and for real after it.</li>
 * </ul>
 * The loop is that servlet's, moved here unchanged except that it counts
 * what it writes; the servlet and the job keep their own copies.
 */
public class ImportCRFDataPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportCRFDataPersistenceService.class);

    /** A stored value the save replaced with a different one. */
    public record OverwrittenValue(int itemDataId, String itemOid, String oldValue, String newValue,
                                   int studyEventId) {}

    /**
     * What {@link #save} wrote.
     *
     * @param inserted          item_data rows created
     * @param overwritten       stored values replaced by a different value
     * @param unchanged         stored values written again with the same value
     * @param discrepancyNotes  Failed Validation Check notes filed (threads, not
     *                          counting their child notes)
     * @param overwrites        the replaced values, for the reason-for-change
     *                          record
     */
    public record SaveResult(int inserted, int overwritten, int unchanged, int discrepancyNotes,
                             List<OverwrittenValue> overwrites) {}

    private final DataSource ds;

    public ImportCRFDataPersistenceService(DataSource ds) {
        this.ds = ds;
    }

    /**
     * Legacy {@code VerifyImportedCRFDataServlet} {@code action=save}.
     *
     * @param wrappers            what {@link ImportCRFDataService#lookupValidationErrors}
     *                            returned, less any values the caller chose not to write
     * @param importedCRFStatuses {@link ImportCRFDataService#fetchEventCRFStatuses}
     */
    public SaveResult save(List<DisplayItemBeanWrapper> wrappers, Map<Integer, String> importedCRFStatuses,
                           UserAccountBean ub, StudyBean currentStudy) throws Exception {
        ItemDataDAO itemDataDao = new ItemDataDAO(ds);
        itemDataDao.setFormatDates(false);
        EventCRFDAO eventCrfDao = new EventCRFDAO(ds);
        CrfBusinessLogicHelper crfBusinessLogicHelper = new CrfBusinessLogicHelper(ds);
        int inserted = 0;
        int overwritten = 0;
        int unchanged = 0;
        int discrepancyNotes = 0;
        List<OverwrittenValue> overwrites = new ArrayList<>();

        for (DisplayItemBeanWrapper wrapper : wrappers) {
            boolean resetSDV = false;
            int eventCrfBeanId = -1;
            EventCRFBean eventCrfBean = new EventCRFBean();

            if (wrapper.isSavable()) {
                ArrayList<Integer> eventCrfInts = new ArrayList<>();
                for (DisplayItemBean displayItemBean : wrapper.getDisplayItemBeans()) {
                    eventCrfBeanId = displayItemBean.getData().getEventCRFId();
                    eventCrfBean = (EventCRFBean) eventCrfDao.findByPK(eventCrfBeanId);
                    ItemDataBean itemDataBean = itemDataDao.findByItemIdAndEventCRFIdAndOrdinal(
                            displayItemBean.getItem().getId(), eventCrfBean.getId(),
                            displayItemBean.getData().getOrdinal());
                    if (wrapper.isOverwrite() && itemDataBean.getStatus() != null) {
                        String oldValue = itemDataBean.getValue();
                        if (!itemDataBean.getValue().equals(displayItemBean.getData().getValue())) {
                            resetSDV = true;
                            overwritten++;
                            overwrites.add(new OverwrittenValue(itemDataBean.getId(),
                                    displayItemBean.getItem().getOid(), oldValue,
                                    displayItemBean.getData().getValue(), eventCrfBean.getStudyEventId()));
                        } else {
                            unchanged++;
                        }
                        itemDataBean.setUpdatedDate(new Date());
                        itemDataBean.setUpdater(ub);
                        itemDataBean.setValue(displayItemBean.getData().getValue());
                        itemDataDao.update(itemDataBean);
                        // need to set pk here in order to create dn
                        displayItemBean.getData().setId(itemDataBean.getId());
                    } else {
                        resetSDV = true;
                        itemDataDao.create(displayItemBean.getData());
                        ItemDataBean itemDataBean2 = itemDataDao.findByItemIdAndEventCRFIdAndOrdinal(
                                displayItemBean.getItem().getId(), eventCrfBean.getId(),
                                displayItemBean.getData().getOrdinal());
                        displayItemBean.getData().setId(itemDataBean2.getId());
                        inserted++;
                    }
                    ItemDAO idao = new ItemDAO(ds);
                    ItemBean ibean = (ItemBean) idao.findByPK(displayItemBean.getData().getItemId());
                    String itemOid = displayItemBean.getItem().getOid() + "_" + wrapper.getStudyEventRepeatKey() + "_"
                            + displayItemBean.getData().getOrdinal() + "_" + wrapper.getStudySubjectOid();
                    if (wrapper.getValidationErrors().containsKey(itemOid)) {
                        ArrayList<String> messageList = wrapper.getValidationErrors().get(itemOid);
                        for (String message : messageList) {
                            DiscrepancyNoteBean parentDn = ImportSpringJob.createDiscrepancyNote(ibean, message,
                                    eventCrfBean, displayItemBean, null, ub, ds, currentStudy);
                            ImportSpringJob.createDiscrepancyNote(ibean, message, eventCrfBean, displayItemBean,
                                    parentDn.getId(), ub, ds, currentStudy);
                            discrepancyNotes++;
                        }
                    }
                    if (!eventCrfInts.contains(Integer.valueOf(eventCrfBean.getId()))) {
                        String eventCRFStatus = importedCRFStatuses.get(Integer.valueOf(eventCrfBean.getId()));
                        if (eventCRFStatus != null && eventCRFStatus.equals(DataEntryStage.INITIAL_DATA_ENTRY.getName())
                                && eventCrfBean.getStatus().isAvailable()) {
                            crfBusinessLogicHelper.markCRFStarted(eventCrfBean, ub);
                        } else {
                            crfBusinessLogicHelper.markCRFComplete(eventCrfBean, ub);
                        }
                        eventCrfInts.add(Integer.valueOf(eventCrfBean.getId()));
                    }
                }
                // Reset the SDV status if item data has been changed or added
                if (eventCrfBean != null && resetSDV)
                    eventCrfDao.setSDVStatus(false, ub.getId(), eventCrfBean.getId());
            }
        }
        LOG.info("CRF import saved: inserted={} overwritten={} unchanged={} notes={} (user={})",
                inserted, overwritten, unchanged, discrepancyNotes, ub.getName());
        return new SaveResult(inserted, overwritten, unchanged, discrepancyNotes, overwrites);
    }

    /**
     * Legacy {@code VerifyImportedCRFDataServlet.ruleRunSetup}: the rule
     * containers for the imported subjects, dry-run once before the save.
     * Empty when the study has no rules or no rule service is given.
     */
    public List<ImportDataRuleRunnerContainer> ruleRunSetup(ODMContainer odmContainer, StudyBean studyBean,
                                                            UserAccountBean userBean,
                                                            RuleSetServiceInterface ruleSetService) {
        List<ImportDataRuleRunnerContainer> containers = new ArrayList<>();
        if (odmContainer == null || ruleSetService == null) return containers;
        ArrayList<SubjectDataBean> subjectDataBeans = odmContainer.getCrfDataPostImportContainer().getSubjectData();
        if (ruleSetService.getCountByStudy(studyBean) > 0) {
            for (SubjectDataBean subjectDataBean : subjectDataBeans) {
                ImportDataRuleRunnerContainer container = new ImportDataRuleRunnerContainer();
                container.initRuleSetsAndTargets(ds, studyBean, subjectDataBean, ruleSetService);
                if (container.getShouldRunRules())
                    containers.add(container);
            }
            if (!containers.isEmpty())
                ruleSetService.runRulesInImportData(containers, studyBean, userBean, ExecutionMode.DRY_RUN);
        }
        return containers;
    }

    /**
     * Legacy {@code VerifyImportedCRFDataServlet.runRules} with
     * {@link ExecutionMode#SAVE} after the save: the rule actions' warnings,
     * one line per action.
     */
    public List<String> runRules(StudyBean studyBean, UserAccountBean userBean,
                                 List<ImportDataRuleRunnerContainer> containers,
                                 RuleSetServiceInterface ruleSetService) {
        List<String> messages = new ArrayList<>();
        if (containers != null && !containers.isEmpty() && ruleSetService != null) {
            HashMap<String, ArrayList<String>> summary = ruleSetService.runRulesInImportData(containers, studyBean,
                    userBean, ExecutionMode.SAVE);
            if (summary != null) {
                for (Map.Entry<String, ArrayList<String>> e : summary.entrySet()) {
                    StringBuilder mesg = new StringBuilder(e.getKey() + " : ");
                    for (String s : e.getValue()) {
                        mesg.append(s).append(", ");
                    }
                    messages.add(mesg.toString());
                }
            }
        }
        return messages;
    }
}
