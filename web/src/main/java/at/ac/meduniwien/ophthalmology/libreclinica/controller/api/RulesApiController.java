/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.DiscrepancyNoteActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.EmailActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.EventActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.HideActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.InsertActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.NotificationActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.PropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RandomizeActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.RuleActionRunLogBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ShowActionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.StratificationFactorBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E RX.1 — read-only rules viewer.
 *
 * <p>Surfaces the legacy {@code rule_set} / {@code rule_set_rule} /
 * {@code rule_action} graph as a clean JSON shape so operators can
 * see what rules attach to their study without dropping into raw
 * SQL or the legacy JSP grid.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/rule-sets} — list rule_sets for the
 *       active study, with their {@code rule_set_rule} + action
 *       graphs inlined</li>
 *   <li>{@code GET /api/v1/rule-sets/{id}} — full detail for one
 *       rule_set</li>
 *   <li>{@code GET /api/v1/rule-sets/{id}/run-log} — paged fire
 *       history (RX.1b)</li>
 * </ul>
 *
 * <p>Authorization: any authenticated user on the active study can
 * read. Rules visibility is a study-membership concern, not a
 * sysadmin gate — mirrors the legacy {@code ViewRuleAssignmentServlet}
 * which has no role check beyond "authenticated user with active
 * study".
 *
 * <p>Pagination + filtering: the first cut returns the full
 * rule_set list for the active study (typically dozens; not 1000s).
 * A {@code ?page=N&pageSize=M} surface can ride on top via
 * {@code RuleSetRuleDao.getWithFilterAndSort} when the dataset
 * grows.
 */
@RestController
@RequestMapping("/api/v1/rule-sets")
@Tag(name = "Rules", description = "Read-only view of the rule_set graph on the active study.")
public class RulesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RulesApiController.class);

    private final DataSource dataSource;
    private final RuleSetDao ruleSetDao;
    private final RuleActionRunLogDao ruleActionRunLogDao;

    @Autowired
    public RulesApiController(@Qualifier("dataSource") DataSource dataSource,
                              RuleSetDao ruleSetDao,
                              RuleActionRunLogDao ruleActionRunLogDao) {
        this.dataSource = dataSource;
        this.ruleSetDao = ruleSetDao;
        this.ruleActionRunLogDao = ruleActionRunLogDao;
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/rule-sets                                              */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @Transactional(readOnly = true)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = RuleSetDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        StudyBean study = (StudyBean) session.getAttribute("study");

        ArrayList<RuleSetBean> beans = ruleSetDao.findAllByStudy(study);
        List<RuleSetDto> out = new ArrayList<>(beans.size());
        for (RuleSetBean rs : beans) {
            out.add(toDto(rs));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/rule-sets/{id}                                         */
    /* ----------------------------------------------------------------- */

    @GetMapping("/{ruleSetId}")
    @Transactional(readOnly = true)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> getOne(@PathVariable("ruleSetId") int ruleSetId,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        StudyBean study = (StudyBean) session.getAttribute("study");

        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }
        return ResponseEntity.ok(toDto(rs));
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/rule-sets/{id}/run-log                                 */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E RX.1b — paged fire history for one rule_set.
     *
     * <p>Walks the rule_set's attached {@code rule_set_rule} rows to
     * gather every {@code rule.oc_oid} the set fires, then asks the
     * DAO for matching {@code rule_action_run_log} rows. The
     * underlying table stores {@code rule_oc_oid} (the rule's OID),
     * not a {@code rule_set_id}, so the join goes via the attached
     * rules rather than directly by rule_set primary key.
     *
     * <p>Ordering is by run-log {@code id} DESC — the table has no
     * timestamp column, but the auto-increment id matches insertion
     * order in practice. See {@link RuleActionRunLogDao#findByRuleOids}
     * for the schema-limitation note.
     *
     * <p>Pagination: {@code ?limit=N&offset=M} (defaults 100 / 0).
     * Negative or zero limit / negative offset returns 400 — the SPA
     * is the only caller and we'd rather reject malformed paging than
     * silently coerce.
     */
    @GetMapping("/{ruleSetId}/run-log")
    @Transactional(readOnly = true)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = RuleActionRunLogDto.class)))
    public ResponseEntity<?> getRunLog(@PathVariable("ruleSetId") int ruleSetId,
                                       @RequestParam(value = "limit", defaultValue = "100") int limit,
                                       @RequestParam(value = "offset", defaultValue = "0") int offset,
                                       HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        if (limit <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "limit must be a positive integer (got " + limit + ")"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "offset must be zero or positive (got " + offset + ")"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");

        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }

        // Gather rule OIDs from attached rules (the run-log stores
        // rule_oc_oid, not rule_set_id).
        List<String> ruleOids = new ArrayList<>();
        if (rs.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                RuleBean rule = rsr.getRuleBean();
                if (rule != null && rule.getOid() != null && !rule.getOid().isEmpty()) {
                    ruleOids.add(rule.getOid());
                }
            }
        }

        List<RuleActionRunLogBean> beans = ruleActionRunLogDao.findByRuleOids(ruleOids, limit, offset);
        List<RuleActionRunLogDto> out = new ArrayList<>(beans.size());
        for (RuleActionRunLogBean b : beans) {
            String actionTypeName = b.getActionType() == null ? "" : b.getActionType().name();
            out.add(new RuleActionRunLogDto(
                    b.getId() == null ? 0 : b.getId(),
                    actionTypeName,
                    b.getRuleOid(),
                    b.getItemDataId(),
                    b.getValue(),
                    null /* no timestamp column — see DAO javadoc */));
        }
        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Shared 401 + 400 (no active study) preflight. Rules visibility
     * has no role gate — any authenticated user on the active study
     * can read.
     */
    private static ResponseEntity<?> preflight(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");
        if (study == null || study.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        return null;
    }

    private RuleSetDto toDto(RuleSetBean rs) {
        // Resolve studyEventDefinition + crf + crfVersion via their
        // own legacy DAOs so we don't depend on lazy-loading their
        // associations from Hibernate (the legacy beans are messy
        // about lazy fetch).
        StudyDAO studyDao = new StudyDAO(dataSource);
        // Already in the active study scope (findAllByStudy scoped
        // the read); studyDao is held for symmetry with other
        // controllers but not strictly needed here.

        String sedOid = rs.getStudyEventDefinition() == null ? null
                : rs.getStudyEventDefinition().getOid();
        String sedName = rs.getStudyEventDefinition() == null ? null
                : rs.getStudyEventDefinition().getName();
        String crfOid = rs.getCrf() == null ? null : rs.getCrf().getOid();
        String crfName = rs.getCrf() == null ? null : rs.getCrf().getName();
        String crfVersionOid = rs.getCrfVersion() == null ? null : rs.getCrfVersion().getOid();
        String crfVersionName = rs.getCrfVersion() == null ? null : rs.getCrfVersion().getName();

        List<RuleSetDto.AttachedRuleDto> attached = new ArrayList<>();
        if (rs.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                attached.add(toAttachedRuleDto(rsr));
            }
        }

        return new RuleSetDto(
                rs.getId(),
                rs.getTarget() == null || rs.getTarget().getValue() == null
                        ? "" : rs.getTarget().getValue(),
                sedOid,
                sedName,
                crfOid,
                crfName,
                crfVersionOid,
                crfVersionName,
                rs.isRunSchedule(),
                rs.getRunTime(),
                rs.getStatus() == null ? "" : rs.getStatus().getName(),
                attached);
    }

    private RuleSetDto.AttachedRuleDto toAttachedRuleDto(RuleSetRuleBean rsr) {
        RuleBean rule = rsr.getRuleBean();
        String oid = rule == null ? null : rule.getOid();
        String name = rule == null ? null : rule.getName();
        String desc = rule == null ? null : rule.getDescription();
        String expr = rule == null || rule.getExpression() == null
                ? "" : rule.getExpression().getValue();

        List<RuleSetDto.RuleActionDto> actions = new ArrayList<>();
        if (rsr.getActions() != null) {
            for (RuleActionBean action : rsr.getActions()) {
                actions.add(toRuleActionDto(action));
            }
        }

        return new RuleSetDto.AttachedRuleDto(
                rsr.getId(),
                oid,
                name,
                desc,
                expr,
                rsr.getStatus() == null ? "" : rsr.getStatus().getName(),
                actions);
    }

    private RuleSetDto.RuleActionDto toRuleActionDto(RuleActionBean action) {
        String actionTypeName = action.getActionType() == null
                ? "" : action.getActionType().name();
        boolean evalTo = Boolean.TRUE.equals(action.getExpressionEvaluatesTo());
        String message = null;

        Map<String, Object> typeSpecific = new HashMap<>();

        // Per-type field projection. The base class carries no
        // message field — each subtype owns it (the column is shared
        // via single-table inheritance, but the getter is per-class).
        if (action instanceof DiscrepancyNoteActionBean a) {
            message = a.getMessage();
        } else if (action instanceof EmailActionBean a) {
            message = a.getMessage();
            typeSpecific.put("to", a.getTo());
        } else if (action instanceof ShowActionBean a) {
            message = a.getMessage();
            typeSpecific.put("properties", projectProperties(a.getProperties()));
        } else if (action instanceof HideActionBean a) {
            message = a.getMessage();
            typeSpecific.put("properties", projectProperties(a.getProperties()));
        } else if (action instanceof InsertActionBean a) {
            typeSpecific.put("properties", projectProperties(a.getProperties()));
        } else if (action instanceof EventActionBean a) {
            typeSpecific.put("eventOidReference", a.getOc_oid_reference());
            typeSpecific.put("properties", projectProperties(a.getProperties()));
        } else if (action instanceof NotificationActionBean a) {
            message = a.getMessage();
            typeSpecific.put("to", a.getTo());
            typeSpecific.put("subject", a.getSubject());
        } else if (action instanceof RandomizeActionBean a) {
            typeSpecific.put("properties", projectProperties(a.getProperties()));
            typeSpecific.put("stratificationFactors",
                    projectStratificationFactors(a.getStratificationFactors()));
        }

        return new RuleSetDto.RuleActionDto(
                action.getId() == null ? 0 : action.getId(),
                actionTypeName,
                evalTo,
                message,
                typeSpecific,
                phaseGates(action.getRuleActionRun()));
    }

    private static RuleSetDto.PhaseGatesDto phaseGates(RuleActionRunBean run) {
        if (run == null) {
            return new RuleSetDto.PhaseGatesDto(false, false, false, false, false);
        }
        return new RuleSetDto.PhaseGatesDto(
                Boolean.TRUE.equals(run.getAdministrativeDataEntry()),
                Boolean.TRUE.equals(run.getInitialDataEntry()),
                Boolean.TRUE.equals(run.getDoubleDataEntry()),
                Boolean.TRUE.equals(run.getImportDataEntry()),
                Boolean.TRUE.equals(run.getBatch()));
    }

    private static Map<String, Object> projectProperty(PropertyBean p) {
        if (p == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("oid", p.getOid());
        m.put("value", p.getValue());
        m.put("valueExpression", p.getValueExpression() == null ? null
                : p.getValueExpression().getValue());
        return m;
    }

    private static List<Map<String, Object>> projectProperties(List<PropertyBean> ps) {
        if (ps == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(ps.size());
        for (PropertyBean p : ps) {
            Map<String, Object> entry = projectProperty(p);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> projectStratificationFactors(List<StratificationFactorBean> fs) {
        if (fs == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(fs.size());
        for (StratificationFactorBean f : fs) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("expression", f.getStratificationFactor() == null ? null
                    : f.getStratificationFactor().getValue());
            out.add(entry);
        }
        return out;
    }
}
