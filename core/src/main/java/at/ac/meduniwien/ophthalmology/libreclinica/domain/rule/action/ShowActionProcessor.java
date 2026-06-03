/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.RuleRunner.RuleRunnerMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.DynamicsMetadataService;

import javax.sql.DataSource;

public class ShowActionProcessor implements ActionProcessor {

    DataSource ds;
    DynamicsMetadataService itemMetadataService;
    RuleSetBean ruleSet;

    public ShowActionProcessor(DataSource ds, DynamicsMetadataService itemMetadataService, RuleSetBean ruleSet) {
        this.itemMetadataService = itemMetadataService;
        this.ruleSet = ruleSet;
        this.ds = ds;
    }

    public RuleActionBean execute(RuleRunnerMode ruleRunnerMode, ExecutionMode executionMode, RuleActionBean ruleAction, ItemDataBean itemDataBean,
            String itemData, StudyBean currentStudy, UserAccountBean ub, Object... arguments) {

        switch (executionMode) {
        case DRY_RUN: {
            if (ruleRunnerMode == RuleRunnerMode.DATA_ENTRY || ruleRunnerMode == RuleRunnerMode.RUN_ON_SCHEDULE) {
                return null;
            } else {
                // Phase E.5 RX.3b (2026-06-02): return the dryRun() result rather than
                // discarding it and falling through. The original code had no `return` or
                // `break;` after dryRun() so case SAVE: executed the side effect even on
                // dry-runs from the TestRule / RunRuleSet UIs (modes other than DATA_ENTRY
                // and RUN_ON_SCHEDULE), persisting `dynamics_item_form_metadata` rows that
                // operators believed were only being previewed. dryRun() returns
                // ruleAction unchanged — the same value the caller already passed in.
                return dryRun(ruleAction, itemDataBean, itemData, currentStudy, ub);
            }
        }
        case SAVE: {
            if (ruleRunnerMode == RuleRunnerMode.DATA_ENTRY) {
                return saveAndReturnMessage(ruleAction, itemDataBean, itemData, currentStudy, ub);
            }else  if (ruleRunnerMode == RuleRunnerMode.RUN_ON_SCHEDULE) {
                    return null;
            } else {
                return save(ruleAction, itemDataBean, itemData, currentStudy, ub);
            }
        }
        default:
            return null;
        }
    }

    private RuleActionBean save(RuleActionBean ruleAction, ItemDataBean itemDataBean, String itemData, StudyBean currentStudy, UserAccountBean ub) {
        getItemMetadataService().showNew(itemDataBean.getId(), ((ShowActionBean) ruleAction).getProperties(), ub, ruleSet);
        return ruleAction;
    }

    private RuleActionBean saveAndReturnMessage(RuleActionBean ruleAction, ItemDataBean itemDataBean, String itemData, StudyBean currentStudy,
            UserAccountBean ub) {
        getItemMetadataService().showNew(itemDataBean.getId(), ((ShowActionBean) ruleAction).getProperties(), ub, ruleSet);
        return ruleAction;
    }

    private RuleActionBean dryRun(RuleActionBean ruleAction, ItemDataBean itemDataBean, String itemData, StudyBean currentStudy, UserAccountBean ub) {
        return ruleAction;
    }

    private DynamicsMetadataService getItemMetadataService() {
        return itemMetadataService;
    }

}
