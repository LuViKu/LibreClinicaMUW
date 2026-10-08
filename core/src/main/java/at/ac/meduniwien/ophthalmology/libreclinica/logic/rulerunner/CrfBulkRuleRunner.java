/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBulkExecuteContainerTwo;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ActionProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ActionProcessorFacade;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunLogBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunBean.Phase;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree.OpenClinicaExpressionParser;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

@SuppressWarnings("all")

public class CrfBulkRuleRunner extends RuleRunner {

    public CrfBulkRuleRunner(DataSource ds, String requestURLMinusServletPath, String contextPath, JavaMailSenderImpl mailSender) {
        super(ds, requestURLMinusServletPath, contextPath, mailSender);
    }

    /**
     * Organize objects in a certain way so that we can show to Users on UI.
     * step1 : Get StudyEvent , eventCrf , crfVersion from studyEventId.
     * 
     * @param crfViewSpecificOrderedObjects
     * @param ruleSet
     * @param rule
     * @return
     */
    private HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> populateForCrfBasedRulesView(
            HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> crfViewSpecificOrderedObjects, RuleSetBean ruleSet,
            RuleBean rule, String result, StudyBean currentStudy, List<RuleActionBean> actions) {

        // step1
        StudyEventBean studyEvent =
            (StudyEventBean) getStudyEventDao().findByPK(
                    Integer.valueOf(getExpressionService().getStudyEventDefenitionOrdninalCurated(ruleSet.getTarget().getValue())));
        EventCRFBean eventCrf =
            (EventCRFBean) getEventCrfDao().findAllByStudyEventAndCrfOrCrfVersionOid(studyEvent,
                    getExpressionService().getCrfOid(ruleSet.getTarget().getValue())).get(0);
        CRFVersionBean crfVersion = (CRFVersionBean) getCrfVersionDao().findByPK(eventCrf.getCRFVersionId());

        RuleBulkExecuteContainer key = new RuleBulkExecuteContainer(crfVersion.getName(), rule, result, actions);
        String key2String = getExpressionService().getCustomExpressionUsedToCreateView(ruleSet.getTarget().getValue(), studyEvent.getSampleOrdinal());
        String studyEventDefinitionName = getExpressionService().getStudyEventDefinitionFromExpression(ruleSet.getTarget().getValue(), currentStudy).getName();
        studyEventDefinitionName += " [" + studyEvent.getSampleOrdinal() + "]";
        // String itemGroupName = getExpressionService().getItemGroupNameAndOrdinal(ruleSet.getTarget().getValue());
        // String itemName = getExpressionService().getItemGroupExpression(ruleSet.getTarget().getValue()).getName();

        String itemGroupName = getExpressionService().getItemGroupNameAndOrdinal(ruleSet.getTarget().getValue());
        ItemGroupBean itemGroupBean = getExpressionService().getItemGroupExpression(ruleSet.getTarget().getValue());
        ItemBean itemBean = getExpressionService().getItemExpression(ruleSet.getTarget().getValue(), itemGroupBean);
        String itemName = itemBean.getName();

        RuleBulkExecuteContainerTwo key2 = new RuleBulkExecuteContainerTwo(key2String, studyEvent, studyEventDefinitionName, itemGroupName, itemName);
        StudySubjectBean studySubject = (StudySubjectBean) getStudySubjectDao().findByPK(studyEvent.getStudySubjectId());

        if (crfViewSpecificOrderedObjects.containsKey(key)) {
            HashMap<RuleBulkExecuteContainerTwo, Set<String>> k = crfViewSpecificOrderedObjects.get(key);
            if (k.containsKey(key2)) {
                k.get(key2).add(String.valueOf(studySubject.getLabel()));
            } else {
                HashSet<String> values = new HashSet<String>();
                values.add(String.valueOf(studySubject.getLabel()));
                k.put(key2, values);
            }
        } else {
            HashMap<RuleBulkExecuteContainerTwo, Set<String>> k = new HashMap<RuleBulkExecuteContainerTwo, Set<String>>();
            HashSet<String> values = new HashSet<String>();
            values.add(String.valueOf(studySubject.getLabel()));
            k.put(key2, values);
            crfViewSpecificOrderedObjects.put(key, k);
        }
        return crfViewSpecificOrderedObjects;
    }

    @Deprecated
    public HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> runRulesBulkOLD(List<RuleSetBean> ruleSets,
            ExecutionMode executionMode, StudyBean currentStudy, HashMap<String, String> variableAndValue, UserAccountBean ub) {

        if (variableAndValue == null || variableAndValue.isEmpty()) {
            logger.warn("You must be executing Rules in Batch");
            variableAndValue = new HashMap<String, String>();
        }

        HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> crfViewSpecificOrderedObjects =
            new HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>>();
        for (RuleSetBean ruleSet : ruleSets) {
            for (ExpressionBean expressionBean : ruleSet.getExpressions()) {
                ruleSet.setTarget(expressionBean);

                for (RuleSetRuleBean ruleSetRule : ruleSet.getRuleSetRules()) {
                    String result = null;
                    RuleBean rule = ruleSetRule.getRuleBean();
                    ExpressionObjectWrapper eow = new ExpressionObjectWrapper(ds, currentStudy, rule.getExpression(), ruleSet, variableAndValue);
                    try {
                        OpenClinicaExpressionParser oep = new OpenClinicaExpressionParser(eow);
                        result = (String) oep.parseAndEvaluateExpression(rule.getExpression().getValue());

                        // Actions
                        List<RuleActionBean> actionListBasedOnRuleExecutionResult = ruleSetRule.getActions(result, Phase.BATCH);

                        ItemDataBean itemData = getExpressionService().getItemDataBeanFromDb(ruleSet.getTarget().getValue());
                        if (itemData != null) {
                            Iterator<RuleActionBean> itr = actionListBasedOnRuleExecutionResult.iterator();
                            while (itr.hasNext()) {
                                RuleActionBean ruleActionBean = itr.next();
                                RuleActionRunLogBean ruleActionRunLog =
                                    new RuleActionRunLogBean(ruleActionBean.getActionType(), itemData, itemData.getValue(), ruleSetRule.getRuleBean().getOid());
                                if (getRuleActionRunLogDao().findCountByRuleActionRunLogBean(ruleActionRunLog) > 0) {
                                    itr.remove();
                                }
                            }
                        }

                        List<RuleActionBean> actionBeansToShow = new ArrayList<RuleActionBean>();
                        if (actionListBasedOnRuleExecutionResult.size() > 0) {
                            //if (!dryRun) {
                            for (RuleActionBean ruleAction : actionListBasedOnRuleExecutionResult) {
                                ruleAction.setCuratedMessage(curateMessage(ruleAction, ruleSetRule));
                                // getDiscrepancyNoteService().saveFieldNotes(ruleAction.getSummary(), itemDataBeanId, "ItemData", currentStudy, ub);
                                ActionProcessor ap =
                                    ActionProcessorFacade.getActionProcessor(ruleAction.getActionType(), ds, getMailSender(), dynamicsMetadataService, ruleSet,
                                            getRuleActionRunLogDao(), ruleSetRule);
                                RuleActionBean rab =
                                    ap.execute(RuleRunnerMode.RULSET_BULK, executionMode, ruleAction, itemData, DiscrepancyNoteBean.ITEM_DATA, currentStudy,
                                            ub, prepareEmailContents(ruleSet, ruleSetRule, currentStudy, ruleAction));
                                if (rab != null) {
                                    actionBeansToShow.add(ruleAction);
                                }
                            }
                            if (actionBeansToShow.size() > 0) {
                                crfViewSpecificOrderedObjects =
                                    populateForCrfBasedRulesView(crfViewSpecificOrderedObjects, ruleSet, rule, result, currentStudy, actionBeansToShow);
                            }

                        }
                    } catch (OpenClinicaSystemException osa) {
                        String errorMessage =
                            "RuleSet with target  : " + ruleSet.getTarget().getValue() + " , Ran Rule : " + rule.getName()
                                + " , It resulted in an error due to : " + osa.getMessage();
                        // log error
                        logger.warn(errorMessage);

                    }
                }
            }
        }
        logCrfViewSpecificOrderedObjects(crfViewSpecificOrderedObjects);
        return crfViewSpecificOrderedObjects;
    }

    public HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> runRulesBulk(List<RuleSetBean> ruleSets,
            ExecutionMode executionMode, StudyBean currentStudy, HashMap<String, String> variableAndValue, UserAccountBean ub) {

        if (variableAndValue == null || variableAndValue.isEmpty()) {
            logger.warn("You must be executing Rules in Batch");
            variableAndValue = new HashMap<String, String>();
        }

        HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>> crfViewSpecificOrderedObjects =
            new HashMap<RuleBulkExecuteContainer, HashMap<RuleBulkExecuteContainerTwo, Set<String>>>();
        HashMap<String, ArrayList<EvaluatedAction>> toBeExecuted = new HashMap<String, ArrayList<EvaluatedAction>>();
        for (RuleSetBean ruleSet : ruleSets) {
            String key = getExpressionService().getItemOid(ruleSet.getOriginalTarget().getValue());
            List<EvaluatedAction> allActionContainerListBasedOnRuleExecutionResult = null;
            if (toBeExecuted.containsKey(key)) {
                allActionContainerListBasedOnRuleExecutionResult = toBeExecuted.get(key);
            } else {
                toBeExecuted.put(key, new ArrayList<EvaluatedAction>());
                allActionContainerListBasedOnRuleExecutionResult = toBeExecuted.get(key);
            }
            ItemDataBean itemData = null;

            for (ExpressionBean expressionBean : ruleSet.getExpressions()) {
                ruleSet.setTarget(expressionBean);

                for (RuleSetRuleBean ruleSetRule : ruleSet.getRuleSetRules()) {
                    String result = null;
                    RuleBean rule = ruleSetRule.getRuleBean();
                    ExpressionObjectWrapper eow = new ExpressionObjectWrapper(ds, currentStudy, rule.getExpression(), ruleSet, variableAndValue);
                    try {
                        OpenClinicaExpressionParser oep = new OpenClinicaExpressionParser(eow);
                        result = (String) oep.parseAndEvaluateExpression(rule.getExpression().getValue());
                        itemData = getExpressionService().getItemDataBeanFromDb(ruleSet.getTarget().getValue());

                        // Actions
                        List<RuleActionBean> actionListBasedOnRuleExecutionResult = ruleSetRule.getActions(result, Phase.BATCH);

                        if (itemData != null) {
                            Iterator<RuleActionBean> itr = actionListBasedOnRuleExecutionResult.iterator();
                            while (itr.hasNext()) {
                                RuleActionBean ruleActionBean = itr.next();
                                RuleActionRunLogBean ruleActionRunLog =
                                    new RuleActionRunLogBean(ruleActionBean.getActionType(), itemData, itemData.getValue(), ruleSetRule.getRuleBean().getOid());
                                if (getRuleActionRunLogDao().findCountByRuleActionRunLogBean(ruleActionRunLog) > 0) {
                                    itr.remove();
                                }
                            }
                        }
                        for (RuleActionBean ruleActionBean : actionListBasedOnRuleExecutionResult) {
                            EvaluatedAction ruleActionContainer = new EvaluatedAction(ruleActionBean, expressionBean, itemData, ruleSet, rule, result);
                            allActionContainerListBasedOnRuleExecutionResult.add(ruleActionContainer);
                        }
                        logger.info("RuleSet with target  : {} , Ran Rule : {}  The Result was : {} , Based on that {} action will be executed in {} mode. ",
                                new Object[] { ruleSet.getTarget().getValue(), rule.getName(), result, actionListBasedOnRuleExecutionResult.size(),
                                    executionMode.name() });
                    } catch (OpenClinicaSystemException osa) {
                    }
                }
            }
        }

        // What a dry run would do, per rule set target, rule and result, the
        // way runRulesBulkOLD grouped it. The heritage loop declared this map
        // but never filled it, so the RunRule dry-run page was always empty
        // while its Submit executed every action.
        Map<PreviewKey, List<RuleActionBean>> preview = new LinkedHashMap<PreviewKey, List<RuleActionBean>>();
        for (Map.Entry<String, ArrayList<EvaluatedAction>> entry : toBeExecuted.entrySet()) {
            // Sort the list of actions
            Collections.sort(entry.getValue(), new RuleActionContainerComparator());
            for (EvaluatedAction ruleActionContainer : entry.getValue()) {

                // Point the rule set at the target this action was evaluated
                // for, as the other runners do: the evaluation loop above left
                // it at the last one, and the action processors (an insert's
                // group row and visit, the e-mail contents) read it.
                ruleActionContainer.getRuleSetBean().setTarget(ruleActionContainer.getExpressionBean());
                ruleActionContainer.getRuleAction().setCuratedMessage(
                        curateMessage(ruleActionContainer.getRuleAction(), ruleActionContainer.getRuleAction().getRuleSetRule()));
                ActionProcessor ap =
                    ActionProcessorFacade.getActionProcessor(ruleActionContainer.getRuleAction().getActionType(), ds, getMailSender(), dynamicsMetadataService,
                            ruleActionContainer.getRuleSetBean(), getRuleActionRunLogDao(), ruleActionContainer.getRuleAction().getRuleSetRule());
                RuleActionBean shown = ap.execute(RuleRunnerMode.RULSET_BULK, executionMode, ruleActionContainer.getRuleAction(), ruleActionContainer.getItemDataBean(),
                        DiscrepancyNoteBean.ITEM_DATA, currentStudy, ub, prepareEmailContents(ruleActionContainer.getRuleSetBean(), ruleActionContainer
                                .getRuleAction().getRuleSetRule(), currentStudy, ruleActionContainer.getRuleAction()));
                // Only the dry run has a preview page; after a save the servlet
                // goes to the rule list and the map is not read.
                if (executionMode == ExecutionMode.DRY_RUN && shown != null) {
                    preview.computeIfAbsent(ruleActionContainer.previewKey(), k -> new ArrayList<RuleActionBean>())
                            .add(ruleActionContainer.getRuleAction());
                }
            }
        }
        // Built after every action ran: the lookups below point each rule set
        // at the target the action was evaluated for, and put back the target
        // the execution loop saw.
        for (Map.Entry<PreviewKey, List<RuleActionBean>> theEntry : preview.entrySet()) {
            PreviewKey key = theEntry.getKey();
            ExpressionBean runTarget = key.ruleSet.getTarget();
            key.ruleSet.setTarget(key.target);
            try {
                crfViewSpecificOrderedObjects =
                    populateForCrfBasedRulesView(crfViewSpecificOrderedObjects, key.ruleSet, key.rule, key.result, currentStudy, theEntry.getValue());
            } finally {
                key.ruleSet.setTarget(runTarget);
            }
        }
        //logCrfViewSpecificOrderedObjects(crfViewSpecificOrderedObjects);
        return crfViewSpecificOrderedObjects;
    }

    /** An action to run, with the rule and the result that selected it. */
    private static final class EvaluatedAction extends RuleActionContainer {
        private final RuleBean rule;
        private final String result;

        EvaluatedAction(RuleActionBean ruleAction, ExpressionBean target, ItemDataBean itemData, RuleSetBean ruleSet, RuleBean rule, String result) {
            super(ruleAction, target, itemData, ruleSet);
            this.rule = rule;
            this.result = result;
        }

        PreviewKey previewKey() {
            return new PreviewKey(getRuleSetBean(), getExpressionBean(), rule, result);
        }
    }

    /**
     * One rule evaluation: a rule set at one of its targets, a rule and its
     * result. The beans are compared by identity: RuleSetBean's hashCode
     * follows its mutable target.
     */
    private static final class PreviewKey {
        final RuleSetBean ruleSet;
        final ExpressionBean target;
        final RuleBean rule;
        final String result;

        PreviewKey(RuleSetBean ruleSet, ExpressionBean target, RuleBean rule, String result) {
            this.ruleSet = ruleSet;
            this.target = target;
            this.rule = rule;
            this.result = result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PreviewKey)) {
                return false;
            }
            PreviewKey other = (PreviewKey) obj;
            return ruleSet == other.ruleSet && target == other.target && rule == other.rule && Objects.equals(result, other.result);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(ruleSet), System.identityHashCode(target), System.identityHashCode(rule), result);
        }
    }

}
