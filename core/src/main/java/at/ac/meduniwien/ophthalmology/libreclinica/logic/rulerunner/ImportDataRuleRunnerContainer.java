/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.FormDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.StudyEventDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemGroupMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunBean.Phase;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import org.springframework.transaction.annotation.Transactional;

public class ImportDataRuleRunnerContainer {
    /**
     * Key is target itemOID
     */
    private HashMap<String, ArrayList<RuleActionContainer>> RuleActionContainerMap;
    private List<RuleSetBean> importDataTrueRuleSets;
    /**
     * Key is full expression; Value is item data value to be imported.
     */
    private Map<String, String> variableAndValue;
    private String studyOid;
    private String studySubjectOid;
    private Boolean shouldRunRules;

    /**
     * Populate importDataTrueRuleSets and variableAndValue.
     * Precondition: import data file passed validation which means all OIDs are not empty.
     * @param ds
     * @param studyBean
     * @param subjectDataBean
     * @param ruleSetService
     */
    @Transactional
    public void initRuleSetsAndTargets(DataSource ds, StudyBean studyBean, SubjectDataBean subjectDataBean, RuleSetServiceInterface ruleSetService) {
        this.shouldRunRules = this.shouldRunRules == null ? Boolean.FALSE : this.shouldRunRules;
        this.importDataTrueRuleSets = this.importDataTrueRuleSets == null ? new ArrayList<RuleSetBean>() : this.importDataTrueRuleSets;
        this.variableAndValue = this.variableAndValue == null ? new HashMap<String, String>() : this.variableAndValue;
        studyOid = studyBean.getOid();
        studySubjectOid = subjectDataBean.getSubjectOID();
        StudySubjectBean studySubject =
                new StudySubjectDAO(ds).findByOid(studySubjectOid);

        HashMap<String, StudyEventDefinitionBean> seds = new HashMap<String, StudyEventDefinitionBean>();
        HashMap<String, CRFVersionBean> cvs = new HashMap<String, CRFVersionBean>();
        ArrayList<StudyEventDataBean> studyEventDataBeans = subjectDataBean.getStudyEventData();
        for (StudyEventDataBean studyEventDataBean : studyEventDataBeans) {
            String sedOid = studyEventDataBean.getStudyEventOID();
            StudyEventDefinitionBean sed;
            if(seds.containsKey(sedOid))
                sed = seds.get(sedOid);
            else {
                sed = new StudyEventDefinitionDAO(ds).findByOid(sedOid);
                seds.put(sedOid, sed);
            }
            ArrayList<FormDataBean> formDataBeans = studyEventDataBean.getFormData();
            for (FormDataBean formDataBean : formDataBeans) {
                String cvOid = formDataBean.getFormOID();
                CRFVersionBean crfVersion;
                if(cvs.containsKey(cvOid))
                    crfVersion = cvs.get(cvOid);
                else {
                    crfVersion = new CRFVersionDAO(ds).findByOid(cvOid);
                    cvs.put(cvOid, crfVersion);
                }
                String sedOrd = studyEventDataBean.getStudyEventRepeatKey();
                Integer sedOrdinal = sedOrd != null && !sedOrd.isEmpty() ? Integer.valueOf(sedOrd) : 1;
                StudyEventBean studyEvent = (StudyEventBean)new StudyEventDAO(ds).findByStudySubjectIdAndDefinitionIdAndOrdinal(
                        studySubject.getId(), sed.getId(), sedOrdinal);
                List<RuleSetBean> ruleSets = ruleSetService.getRuleSetsByCrfStudyAndStudyEventDefinition(studyBean, sed, crfVersion);
                //Set<String> targetItemOids = new HashSet<String>();
                if(ruleSets != null && !ruleSets.isEmpty()) {
                    ruleSets = filterByImportDataEntryTrue(ruleSets);
                    if(ruleSets != null && !ruleSets.isEmpty()) {
                        ruleSets = ruleSetService.filterByStatusEqualsAvailable(ruleSets);
                        ruleSets = ruleSetService.filterRuleSetsByStudyEventOrdinal(ruleSets, studyEvent, crfVersion, sed);
                        //ruleSets = ruleSetService.filterRuleSetsByHiddenItems(ruleSets, eventCrfBean, crfVersion, new ArrayList<ItemBean>());
                        shouldRunRules = ruleSetService.shouldRunRulesForRuleSets(ruleSets, Phase.IMPORT);
                        if(shouldRunRules != null && shouldRunRules == Boolean.TRUE) {
                            //targetItemOids = collectTargetItemOids(ruleSets);

                            HashMap<String, Integer> grouped = new HashMap<String, Integer>();
                            ArrayList<ImportItemGroupDataBean> itemGroupDataBeans = formDataBean.getItemGroupData();
                            for (ImportItemGroupDataBean itemGroupDataBean : itemGroupDataBeans) {
                                ArrayList<ImportItemDataBean> itemDataBeans = itemGroupDataBean.getItemData();
                                for (ImportItemDataBean importItemDataBean : itemDataBeans) {
                                    //if(targetItemOids.contains(importItemDataBean.getItemOID())) {
                                        ItemBean item = new ItemDAO(ds).findByOid(importItemDataBean.getItemOID()).get(0);
                                        String igOid = itemGroupDataBean.getItemGroupOID();
                                        String igOrd = itemGroupDataBean.getItemGroupRepeatKey();
                                        Integer igOrdinal = igOrd != null && !igOrd.isEmpty() ? Integer.valueOf(igOrd) : 1;
                                        //
                                        //logic from DataEntryServlet method: populateRuleSpecificHashMaps()
                                        if(isRepeatIGForSure(ds, crfVersion.getId(), igOid, igOrdinal, item.getId())) {
                                            String key1 = igOid + "[" + igOrdinal + "]." + importItemDataBean.getItemOID();
                                            String key = igOid + "." + importItemDataBean.getItemOID();
                                            variableAndValue.put(key1, importItemDataBean.getValue());
                                            if (grouped.containsKey(key)) {
                                                grouped.put(key, grouped.get(key) + 1);
                                            } else {
                                                grouped.put(key, 1);
                                            }
                                        } else {
                                            variableAndValue.put(importItemDataBean.getItemOID(), importItemDataBean.getValue());
                                            grouped.put(importItemDataBean.getItemOID(), 1);
                                        }
                                        //
                                    //}
                                }
                            }
                            ruleSets = ruleSetService.solidifyGroupOrdinalsUsingFormProperties(ruleSets, grouped);
                            importDataTrueRuleSets.addAll(ruleSets);
                        }
                    }
                }
            }
        }
    }

    @Transactional
    private List<RuleSetBean> filterByImportDataEntryTrue(List<RuleSetBean> ruleSetBeans) {
        List<RuleSetBean> ruleSets = ruleSetBeans;
        if(ruleSets != null) {
            for (RuleSetBean ruleSet : ruleSets) {
                if(ruleSet.getRuleSetRules() != null) {
                    List<RuleSetRuleBean> ruleSetRules = ruleSet.getRuleSetRules();
                    for (RuleSetRuleBean ruleSetRule : ruleSetRules) {
                        List<RuleActionBean> ruleActions = ruleSetRule.getActions();
                        if(ruleActions != null && ruleActions.size() > 0) {
                            for(Iterator<RuleActionBean> ra = ruleActions.iterator(); ra.hasNext();) {
                                RuleActionBean ruleAction = ra.next();
                                if(!ruleAction.getRuleActionRun().canRun(Phase.IMPORT)) {
                                    ra.remove();
                                }
                            }
                        }
                    }
                }
            }
        }
        return ruleSets;
    }

    private boolean isRepeatIGForSure(DataSource ds, Integer crfVersionId,  String itemGroupOid, Integer igOrdinal, Integer itemId) {
        boolean isRepeatForSure = igOrdinal != null && igOrdinal > 1 ? true : false;
        if(!isRepeatForSure) {
            if(itemGroupOid.endsWith("_UNGROUPED") || itemGroupOid.contains("_UNGROUPED_")) isRepeatForSure = false;
            else {
                ItemGroupMetadataBean itemGroupMetadataBean =
                    (ItemGroupMetadataBean)new ItemGroupMetadataDAO(ds).findByItemAndCrfVersion(itemId, crfVersionId);
                isRepeatForSure = itemGroupMetadataBean.isRepeatingGroup();
            }
        }
        return isRepeatForSure;
    }


    public String getStudyOid() {
        return studyOid;
    }

    public void setStudyOid(String studyOid) {
        this.studyOid = studyOid;
    }

    public String getStudySubjectOid() {
        return studySubjectOid;
    }

    public void setStudySubjectOid(String studySubjectOid) {
        this.studySubjectOid = studySubjectOid;
    }

    public List<RuleSetBean> getImportDataTrueRuleSets() {
        return importDataTrueRuleSets;
    }

    public void setImportDataTrueRuleSets(List<RuleSetBean> importDataTrueRuleSets) {
        this.importDataTrueRuleSets = importDataTrueRuleSets;
    }

    public Map<String, String> getVariableAndValue() {
        return variableAndValue;
    }

    public void setVariableAndValue(Map<String, String> variableAndValue) {
        this.variableAndValue = variableAndValue;
    }

    public HashMap<String, ArrayList<RuleActionContainer>> getRuleActionContainerMap() {
        return RuleActionContainerMap;
    }

    public void setRuleActionContainerMap(HashMap<String, ArrayList<RuleActionContainer>> ruleActionContainerMap) {
        RuleActionContainerMap = ruleActionContainerMap;
    }

    public Boolean getShouldRunRules() {
        return shouldRunRules;
    }

    public void setShouldRunRules(Boolean shouldRunRules) {
        this.shouldRunRules = shouldRunRules;
    }
}
