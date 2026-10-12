/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.RuleRunner.RuleRunnerMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.DynamicsMetadataService;

import javax.sql.DataSource;

@SuppressWarnings("all")

public class InsertActionProcessor implements ActionProcessor {

    DataSource ds;
    DynamicsMetadataService itemMetadataService;
    RuleActionRunLogDao ruleActionRunLogDao;
    RuleSetBean ruleSet;
    RuleSetRuleBean ruleSetRule;

    public InsertActionProcessor(DataSource ds, DynamicsMetadataService itemMetadataService, RuleActionRunLogDao ruleActionRunLogDao, RuleSetBean ruleSet,
            RuleSetRuleBean ruleSetRule) {
        this.itemMetadataService = itemMetadataService;
        this.ruleSet = ruleSet;
        this.ruleSetRule = ruleSetRule;
        this.ruleActionRunLogDao = ruleActionRunLogDao;
        this.ds = ds;
    }

    @Override
    public RuleActionBean execute(RuleRunnerMode ruleRunnerMode, ExecutionMode executionMode, RuleActionBean ruleAction, ItemDataBean itemDataBean,
            String itemData, StudyBean currentStudy, UserAccountBean ub, Object... arguments) {

        switch (executionMode) {
        case DRY_RUN: {
            if (ruleRunnerMode == RuleRunnerMode.DATA_ENTRY) {
                return null;
            } else {
                // Phase E.5 RX.3b (2026-06-02): return the dryRun() result rather than
                // discarding it and falling through. See ShowActionProcessor for the full
                // rationale — same bug shape, same fix; dryRun() here previously fell
                // through to case SAVE: which called itemMetadataService.insert(...)
                // even on operator preview runs. This is the most consequential of the
                // three — Insert actually writes new item_data values.
                return dryRun(ruleAction);
            }
        }
        case SAVE: {
            if (ruleRunnerMode == RuleRunnerMode.DATA_ENTRY) {
                save(ruleAction, itemDataBean, ub);
            } else if(ruleRunnerMode == RuleRunnerMode.IMPORT_DATA) {
                saveWithStatusUpdated(ruleAction, itemDataBean, ub);
            } else {
                save(ruleAction, itemDataBean, ub);
            }
        }
        default:
            return null;
        }
    }

    private RuleActionBean saveWithStatusUpdated(RuleActionBean ruleAction, ItemDataBean itemDataBean, UserAccountBean ub) {
        itemDataBean.setStatus(Status.UNAVAILABLE);
        getItemMetadataService().insert(itemDataBean, ((InsertActionBean) ruleAction).getProperties(), ub, ruleSet,null);
        ruleActionRunLogSaveOrUpdate(ruleAction, itemDataBean);
        return null;
    }

    private RuleActionBean save(RuleActionBean ruleAction, ItemDataBean itemDataBean, UserAccountBean ub) {
        getItemMetadataService().insert(itemDataBean.getId(), ((InsertActionBean) ruleAction).getProperties(), ub, ruleSet,null);
        ruleActionRunLogSaveOrUpdate(ruleAction, itemDataBean);
        return null;
    }

    private void ruleActionRunLogSaveOrUpdate(RuleActionBean ruleAction, ItemDataBean itemDataBean) {
        RuleActionRunLogBean ruleActionRunLog =
                new RuleActionRunLogBean(ruleAction.getActionType(), itemDataBean, itemDataBean.getValue(), ruleSetRule.getRuleBean().getOid());
        if (ruleActionRunLogDao.findCountByRuleActionRunLogBean(ruleActionRunLog) > 0) {
        } else {
            ruleActionRunLogDao.saveOrUpdate(ruleActionRunLog);
        }
    }

    private RuleActionBean dryRun(RuleActionBean ruleAction) {
        return ruleAction;
    }

    private DynamicsMetadataService getItemMetadataService() {
        return itemMetadataService;
    }

}
