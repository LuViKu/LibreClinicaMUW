/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ViewRuleAssignmentFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ViewRuleAssignmentSort;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.datatable.DataTableRequest;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DataTables-protocol JSON endpoint for the "Manage Rules" table.
 * Phase B.4 jmesa PR 7a (cohort 5a) — replaces the server-rendered
 * HTML blob the deleted {@code ViewRuleAssignmentTableFactory}
 * (945 LOC) dumped into {@code listRuleSets2.jsp} /
 * {@code listRuleSetsDesigner.jsp}.
 *
 * <p>18 static columns, with multi-action rendering per row. Complex
 * cells (validations, executeOn, actionType, actionSummary) are
 * pre-rendered as plain-text values; the JS fragment just injects
 * them. Per-row {@code availableActions} encodes the legacy
 * ActionsCellEditor policy (view always; execute/remove only when
 * the rule is AVAILABLE; restore only when DELETED; extract/test
 * always).
 */
public class ViewRuleAssignmentDataServlet extends SecureController {

    private static final long serialVersionUID = 1L;
    private static final int MAX_PAGE_LENGTH = 500;

    private static final List<String> COLUMN_WHITELIST = Arrays.asList(
            "ruleSetRunSchedule", "ruleSetRunTime", "targetValue",
            "studyEventDefinitionName", "crfName", "crfVersionName",
            "groupLabel", "itemName", "ruleName", "ruleOid",
            "ruleSetRuleStatus", "ruleDescription", "ruleExpressionValue",
            "actionType");

    private Locale locale;

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        locale = LocaleResolver.getLocale(request);
        if (currentRole == null || currentRole.getRole() == null
                || currentRole.getRole().equals(Role.INVALID)) {
            throw new InsufficientPermissionException(Page.MENU_SERVLET,
                    resexception.getString("you_may_not_perform_administrative_functions"), "1");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processRequest() throws Exception {
        try {
            doProcessRequest();
        } catch (Exception e) {
            // Emit JSON error instead of SecureController's HTML
            // error.jsp forward — the JS client expects JSON.
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(500);
            Map<String, Object> err = new HashMap<>();
            err.put("error", String.valueOf(e));
            try (OutputStream out = response.getOutputStream()) {
                new ObjectMapper().writeValue(out, err);
            }
            throw e;  // re-throw so it still hits SecureController's logger.
        }
    }

    @SuppressWarnings("unchecked")
    private void doProcessRequest() throws Exception {
        java.util.ResourceBundle resword = ResourceBundleProvider.getWordsBundle(locale);

        DataTableRequest dt = DataTableRequest.from(request);
        int length = Math.min(dt.getLength(), MAX_PAGE_LENGTH);
        if (length <= 0) length = MAX_PAGE_LENGTH;
        int rowStart = Math.max(dt.getStart(), 0);
        int rowEnd = rowStart + length;

        ViewRuleAssignmentFilter filter = new ViewRuleAssignmentFilter();
        filter.addFilter("studyId", currentStudy.getId());
        // Default behaviour from the legacy factory: hide deleted rules
        // unless explicit ruleSetRuleStatus filter was supplied.
        boolean hasStatusFilter = false;
        for (DataTableRequest.Column col : dt.getColumns()) {
            if (col.getSearchValue() == null || col.getSearchValue().isEmpty()) continue;
            if (!COLUMN_WHITELIST.contains(col.getData())) continue;
            filter.addFilter(col.getData(), col.getSearchValue());
            if ("ruleSetRuleStatus".equals(col.getData())) hasStatusFilter = true;
        }
        if (!hasStatusFilter) {
            filter.addFilter("ruleSetRuleStatus", "1");
        }

        ViewRuleAssignmentSort sort = new ViewRuleAssignmentSort();
        String sortColumn = dt.getSortColumnName();
        if (sortColumn != null && COLUMN_WHITELIST.contains(sortColumn)) {
            sort.addSort(sortColumn, dt.getSortDirection());
        } else {
            sort.addSort("itemName", "asc");
        }

        RuleSetServiceInterface ruleSetService = (RuleSetServiceInterface)
                WebApplicationContextUtils.getWebApplicationContext(getServletContext())
                        .getBean("ruleSetService");
        ItemFormMetadataDAO itemFormMetadataDAO = new ItemFormMetadataDAO(sm.getDataSource());

        long totalRows = ruleSetService.getCountWithFilter(filter);
        long filteredRows = totalRows;

        Collection<RuleSetRuleBean> items = ruleSetService.getWithFilterAndSort(
                filter, sort, rowStart, rowEnd);

        // RuleSet hydration cache — the legacy factory called
        // getObjects(ruleSet) per unique rule set; replicate that.
        Map<Integer, RuleSetBean> ruleSetCache = new HashMap<>();

        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (RuleSetRuleBean ruleSetRule : items) {
            RuleSetBean ruleSet;
            int rsId = ruleSetRule.getRuleSetBean().getId();
            if (ruleSetCache.containsKey(rsId)) {
                ruleSet = ruleSetCache.get(rsId);
            } else {
                ruleSet = ruleSetRule.getRuleSetBean();
                ruleSetService.getObjects(ruleSet);
                ruleSetCache.put(rsId, ruleSet);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ruleSetRule.getId());
            row.put("ruleSetId", ruleSet.getId());
            row.put("ruleId", ruleSetRule.getRuleBean() == null ? -1
                    : ruleSetRule.getRuleBean().getId());

            row.put("ruleSetRunSchedule", String.valueOf(ruleSet.isRunSchedule()));
            row.put("ruleSetRunTime", ruleSet.getRunTime());
            row.put("targetValue", ruleSet.getTarget() == null ? "" : ruleSet.getTarget().getValue());
            row.put("studyEventDefinitionName", ruleSet.getStudyEventDefinitionName());
            row.put("crfName", ruleSet.getCrfName());
            row.put("crfVersionName", ruleSet.getCrfVersionName());
            row.put("groupLabel", ruleSet.getGroupLabel());
            row.put("itemName", ruleSet.getItemName());
            row.put("ruleName", ruleSetRule.getRuleBean() == null ? "" : ruleSetRule.getRuleBean().getName());
            row.put("ruleOid", ruleSetRule.getRuleBean() == null ? "" : ruleSetRule.getRuleBean().getOid());
            row.put("ruleSetRuleStatus", ruleSetRule.getStatus() == null
                    ? "" : ruleSetRule.getStatus().getI18nDescription(locale));
            row.put("ruleDescription", ruleSetRule.getRuleBean() == null ? ""
                    : ruleSetRule.getRuleBean().getDescription());
            row.put("ruleExpressionValue", ruleSetRule.getRuleBean() == null
                    || ruleSetRule.getRuleBean().getExpression() == null ? ""
                    : ruleSetRule.getRuleBean().getExpression().getValue());

            row.put("validations", renderValidations(
                    (ItemBean) ruleSet.getItem(),
                    (CRFBean) ruleSet.getCrf(),
                    (CRFVersionBean) ruleSet.getCrfVersion(),
                    itemFormMetadataDAO));

            List<RuleActionBean> actions = ruleSetRule.getActions() == null
                    ? new ArrayList<>() : ruleSetRule.getActions();
            row.put("actionExecuteOn", joinExecuteOn(actions));
            row.put("actionType", joinActionType(actions));
            row.put("actionSummary", joinActionSummary(actions));

            row.put("availableActions", availableActions(ruleSetRule));
            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(col("ruleSetRunSchedule",       resword.getString("view_rule_assignment_run_schedule")));
        columns.add(col("ruleSetRunTime",           resword.getString("view_rule_assignment_run_time")));
        columns.add(col("targetValue",              resword.getString("view_rule_assignment_target")));
        columns.add(col("studyEventDefinitionName", resword.getString("view_rule_assignment_study_event")));
        columns.add(col("crfName",                  resword.getString("view_rule_assignment_crf")));
        columns.add(col("crfVersionName",           resword.getString("view_rule_assignment_version")));
        columns.add(col("groupLabel",               resword.getString("view_rule_assignment_group")));
        columns.add(col("itemName",                 resword.getString("view_rule_assignment_item_name")));
        columns.add(col("ruleName",                 resword.getString("view_rule_assignment_rule_name")));
        columns.add(col("ruleOid",                  resword.getString("view_rule_assignment_rule_oid")));
        columns.add(col("ruleSetRuleStatus",        resword.getString("view_rule_assignment_rule_status")));
        columns.add(col("ruleDescription",          resword.getString("view_rule_assignment_rule_description")));
        columns.add(col("ruleExpressionValue",      resword.getString("view_rule_assignment_expression")));
        columns.add(col("validations",              resword.getString("view_rule_assignment_crf_br_validations")));
        columns.add(col("actionExecuteOn",          resword.getString("view_rule_assignment_execute_on")));
        columns.add(col("actionType",               resword.getString("view_rule_assignment_action_type")));
        columns.add(col("actionSummary",            resword.getString("view_rule_assignment_action_summary")));
        columns.add(col("actions",                  resword.getString("rule_actions")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draw", dt.getDraw());
        payload.put("recordsTotal", totalRows);
        payload.put("recordsFiltered", filteredRows);
        payload.put("data", rows);
        payload.put("columns", columns);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    /**
     * "yes"/"no" — whether the rule's item has any form metadata with
     * a regex validation. Mirrors the legacy
     * {@code ValidationsValueCellEditor} (export mode, simplified).
     */
    private String renderValidations(ItemBean item, CRFBean crf, CRFVersionBean crfVersion,
                                     ItemFormMetadataDAO ifmDAO) {
        if (item == null) return "";
        if (crfVersion != null) {
            ItemFormMetadataBean ifm = ifmDAO.findByItemIdAndCRFVersionId(item.getId(), crfVersion.getId());
            return (ifm.getId() != 0 && ifm.getRegexp() != null && !ifm.getRegexp().isEmpty())
                    ? "yes" : "no";
        }
        if (crf != null) {
            ArrayList<ItemFormMetadataBean> ifms = ifmDAO
                    .findAllByCRFIdItemIdAndHasValidations(crf.getId(), item.getId());
            return ifms.size() > 0 ? "yes" : "no";
        }
        ArrayList<ItemFormMetadataBean> ifms = ifmDAO.findAllByItemIdAndHasValidations(item.getId());
        return ifms.size() > 0 ? "yes" : "no";
    }

    private String joinExecuteOn(List<RuleActionBean> actions) {
        if (actions == null || actions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(" - ");
            sb.append(actions.get(i).getExpressionEvaluatesTo());
        }
        return sb.toString();
    }

    private String joinActionType(List<RuleActionBean> actions) {
        if (actions == null || actions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(" ; ");
            if (actions.get(i).getActionType() != null) {
                sb.append(actions.get(i).getActionType().getDescription());
            }
        }
        return sb.toString();
    }

    private String joinActionSummary(List<RuleActionBean> actions) {
        if (actions == null || actions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(" ; ");
            sb.append(actions.get(i).getSummary());
        }
        return sb.toString();
    }

    /**
     * Per-row action policy mirror of the legacy {@code ActionsCellEditor}.
     * Always: view, extract, test. Conditionally on rule status:
     * execute + remove when AVAILABLE; restore when DELETED.
     */
    private List<String> availableActions(RuleSetRuleBean ruleSetRule) {
        List<String> a = new ArrayList<>();
        a.add("view");
        if (ruleSetRule.getStatus() != Status.DELETED) {
            a.add("execute");
            a.add("remove");
        } else {
            a.add("restore");
        }
        a.add("extract");
        a.add("test");
        return a;
    }

    private static Map<String, Object> col(String key, String title) {
        Map<String, Object> c = new HashMap<>();
        c.put("key", key);
        c.put("title", title);
        return c;
    }

    @Override
    protected String getAdminServlet() {
        return SecureController.ADMIN_SERVLET_CODE;
    }
}
