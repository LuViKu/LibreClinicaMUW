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
import java.util.regex.Pattern;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ActionType;
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
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.Context;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionObjectWrapper;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E RX.1 — read-only rules viewer + RX.4 lifecycle mutations.
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
 *   <li>{@code POST /api/v1/rule-sets/{id}/disable} +
 *       {@code /restore} — soft-delete / undelete a rule_set via
 *       {@code status_id} flip (RX.4)</li>
 *   <li>{@code DELETE /api/v1/rule-sets/{id}} — alias for
 *       {@code /disable}, mirrors legacy
 *       {@code RemoveRuleSetServlet} semantics (RX.4)</li>
 *   <li>{@code POST /api/v1/rule-sets/{id}/rules/{ruleSetRuleId}/disable}
 *       + {@code /restore} — same flip on a single
 *       {@code rule_set_rule} binding (RX.4)</li>
 * </ul>
 *
 * <p>Authorization:
 * <ul>
 *   <li>Reads (RX.1/RX.1b): any authenticated user on the active
 *       study. Mirrors the legacy
 *       {@code ViewRuleAssignmentServlet} which has no role check
 *       beyond "authenticated user with active study".</li>
 *   <li>Writes (RX.4): sysadmin OR study director/coordinator bound
 *       to the active study, via
 *       {@link StudyAdminAuthorization#roleMayEditStudy}. Mirrors
 *       legacy {@code RemoveRuleSetServlet} +
 *       {@code UpdateRuleSetRuleServlet}.</li>
 * </ul>
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
    private final RuleSetRuleDao ruleSetRuleDao;
    private final RuleActionRunLogDao ruleActionRunLogDao;
    private final RuleDao ruleDao;

    @Autowired
    public RulesApiController(@Qualifier("dataSource") DataSource dataSource,
                              RuleSetDao ruleSetDao,
                              RuleSetRuleDao ruleSetRuleDao,
                              RuleActionRunLogDao ruleActionRunLogDao,
                              RuleDao ruleDao) {
        this.dataSource = dataSource;
        this.ruleSetDao = ruleSetDao;
        this.ruleSetRuleDao = ruleSetRuleDao;
        this.ruleActionRunLogDao = ruleActionRunLogDao;
        this.ruleDao = ruleDao;
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
    /* POST /api/v1/rule-sets/{id}/disable                                */
    /* POST /api/v1/rule-sets/{id}/restore                                */
    /* DELETE /api/v1/rule-sets/{id}  (alias for /disable, mirrors        */
    /*   legacy RemoveRuleSetServlet semantics)                           */
    /*   (Phase E RX.4 — rule_set lifecycle)                              */
    /* ----------------------------------------------------------------- */

    /**
     * Soft-delete a {@code rule_set} by flipping its
     * {@code status_id} to {@link Status#DELETED}.
     *
     * <p>Authorization: sysadmin OR study director/coordinator bound
     * to the active study, mirroring
     * {@link StudyAdminAuthorization#roleMayEditStudy}. The legacy
     * {@code RemoveRuleSetServlet}/{@code UpdateRuleSetRuleServlet}
     * gates are identical (sysadmin OR director/coordinator).
     *
     * <p><b>No cascade to child {@code rule_set_rule} rows.</b> The
     * legacy {@code RemoveRuleSetServlet} cascades the DELETED status
     * to every attached {@code rule_set_rule}; this slice keeps the
     * cascade off — disabling at the rule_set level alone is enough
     * to stop the runner from evaluating any rules attached to it
     * (the bulk runner short-circuits on the parent's status before
     * looking at children). Leaving the child rows AVAILABLE keeps a
     * crisp audit trail per binding so a future "restore plus enable
     * these specific rules" UX has the data it needs. The separate
     * {@code /rules/{ruleSetRuleId}/disable} endpoint is the binding-
     * scoped flip operators reach for when they want to silence one
     * rule without dropping the whole set.
     */
    @PostMapping("/{ruleSetId}/disable")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> disableRuleSet(@PathVariable("ruleSetId") int ruleSetId,
                                            HttpSession session) {
        return ruleSetLifecycle(ruleSetId, session, Status.DELETED, "disable");
    }

    @PostMapping("/{ruleSetId}/restore")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> restoreRuleSet(@PathVariable("ruleSetId") int ruleSetId,
                                            HttpSession session) {
        return ruleSetLifecycle(ruleSetId, session, Status.AVAILABLE, "restore");
    }

    /**
     * DELETE alias for {@link #disableRuleSet}. The legacy
     * {@code RemoveRuleSetServlet} is a soft-delete (status flip,
     * not a row delete), so the REST semantic that matches is
     * "DELETE = disable". Behaves exactly as POST /disable.
     */
    @DeleteMapping("/{ruleSetId}")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> deleteRuleSet(@PathVariable("ruleSetId") int ruleSetId,
                                           HttpSession session) {
        return ruleSetLifecycle(ruleSetId, session, Status.DELETED, "disable");
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rule-sets/{id}/rules/{ruleSetRuleId}/disable          */
    /* POST /api/v1/rule-sets/{id}/rules/{ruleSetRuleId}/restore          */
    /* ----------------------------------------------------------------- */

    /**
     * Soft-delete one attached rule (a {@code rule_set_rule} binding)
     * by flipping its {@code status_id} to {@link Status#DELETED}.
     *
     * <p>Mirrors legacy {@code UpdateRuleSetRuleServlet?action=remove}
     * — silences one rule without dropping the whole rule_set.
     */
    @PostMapping("/{ruleSetId}/rules/{ruleSetRuleId}/disable")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> disableAttachedRule(@PathVariable("ruleSetId") int ruleSetId,
                                                 @PathVariable("ruleSetRuleId") int ruleSetRuleId,
                                                 HttpSession session) {
        return attachedRuleLifecycle(ruleSetId, ruleSetRuleId, session, Status.DELETED, "disable");
    }

    @PostMapping("/{ruleSetId}/rules/{ruleSetRuleId}/restore")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> restoreAttachedRule(@PathVariable("ruleSetId") int ruleSetId,
                                                 @PathVariable("ruleSetRuleId") int ruleSetRuleId,
                                                 HttpSession session) {
        return attachedRuleLifecycle(ruleSetId, ruleSetRuleId, session, Status.AVAILABLE, "restore");
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/rule-sets/{id}/schedule                                */
    /*   (Phase E RX.7 — surface run_schedule + run_time)                 */
    /* ----------------------------------------------------------------- */

    /**
     * 24-hour {@code HH:mm} pattern, 00:00 through 23:59. Anchored on
     * both ends so {@code matcher.matches()} guards against trailing
     * whitespace / garbage. Hours allow an optional leading zero (so
     * both {@code 8:30} and {@code 08:30} parse).
     */
    private static final Pattern RUN_TIME_PATTERN =
            Pattern.compile("^([01]?[0-9]|2[0-3]):[0-5][0-9]$");

    /**
     * Phase E RX.5 — operator-supplied OID grammar for new rules.
     *
     * <p>Tightened from the underlying {@code OidGenerator} validator
     * ({@code ^[A-Z_0-9]+$}, max 40) — must start with a letter, then
     * letters / digits / underscores. Mirrors the canonical legacy
     * convention (e.g. {@code RUL_BP_HIGH}) so operator-authored OIDs
     * are visually consistent with the OIDs the import path produces.
     */
    private static final Pattern RULE_OID_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** Max length per legacy {@code rule.oc_oid} column width. */
    private static final int OID_MAX_LENGTH = 40;
    private static final int NAME_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;
    private static final int MESSAGE_MAX_LENGTH = 2000;
    /** Lightweight RFC-5322ish email shape — non-blank + one @ + one ".". */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /**
     * Surface the existing {@code rule_set.run_schedule} +
     * {@code rule_set.run_time} columns to the SPA so operators can
     * enrol / un-enrol a rule_set in the Quartz nightly batch without
     * dropping to raw SQL or re-importing the XML.
     *
     * <p>Authorization: sysadmin OR study director/coordinator bound
     * to the active study, via {@link StudyAdminAuthorization#roleMayEditStudy}.
     * Same gate as the RX.4 lifecycle endpoints — schedule changes are
     * an audit-of-record event so the legacy operator gate carries
     * over.
     *
     * <p>Validation:
     * <ul>
     *   <li>400 if body is missing or {@code runSchedule} is null.</li>
     *   <li>400 if {@code runSchedule == true} and {@code runTime} is
     *       null / blank / not matching {@link #RUN_TIME_PATTERN}.</li>
     *   <li>{@code runTime} is accepted as-is when
     *       {@code runSchedule == false} — the runner ignores it under
     *       that branch, so we don't reject malformed values that the
     *       operator might be keeping around for "I'll turn it back on
     *       later".</li>
     * </ul>
     *
     * <p>Audit: one {@code audit_log_event} row per actually-changed
     * field. So a single PUT can emit 0, 1, or 2 rows
     * ({@code run_schedule} alone, {@code run_time} alone, both, or
     * neither when the operator submits the current values).
     */
    @PutMapping("/{ruleSetId}/schedule")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> setSchedule(@PathVariable("ruleSetId") int ruleSetId,
                                         @RequestBody(required = false) SetRuleSetScheduleRequest body,
                                         HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule_set schedule edit"));
        }

        if (body == null || body.runSchedule() == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { runSchedule: boolean, runTime: 'HH:mm' }"));
        }

        boolean newRunSchedule = body.runSchedule();
        String submittedRunTime = body.runTime();
        if (newRunSchedule) {
            if (submittedRunTime == null || submittedRunTime.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "runTime is required when runSchedule is true (format HH:mm)"));
            }
            if (!RUN_TIME_PATTERN.matcher(submittedRunTime.trim()).matches()) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "runTime must match 24-hour HH:mm (e.g. 08:00 or 22:30); got '"
                                + submittedRunTime + "'"));
            }
        }

        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }

        boolean oldRunSchedule = rs.isRunSchedule();
        String oldRunTime = rs.getRunTime();
        // When scheduling is on, normalise the trimmed value into the
        // bean so the runner reads a canonical form; when scheduling
        // is off, take whatever the operator submitted (or null) at
        // face value — the runner won't read it.
        String newRunTime = newRunSchedule
                ? submittedRunTime.trim()
                : submittedRunTime;

        boolean runScheduleChanged = oldRunSchedule != newRunSchedule;
        boolean runTimeChanged = newRunSchedule
                && !java.util.Objects.equals(oldRunTime, newRunTime);

        if (!runScheduleChanged && !runTimeChanged) {
            // Nothing to do — the SPA submitted the current state.
            // Return the DTO so the caller can refresh its view without
            // a second GET round-trip.
            return ResponseEntity.ok(toDto(rs));
        }

        if (runScheduleChanged) {
            rs.setRunSchedule(newRunSchedule);
        }
        if (runTimeChanged) {
            rs.setRunTime(newRunTime);
        }
        rs.setUpdater(me);
        rs.setUpdatedDate(new java.util.Date());
        ruleSetDao.saveOrUpdate(rs);

        if (runScheduleChanged) {
            writeRuleSetFieldAudit(me, study, rs, "run_schedule",
                    Boolean.toString(oldRunSchedule),
                    Boolean.toString(newRunSchedule));
        }
        if (runTimeChanged) {
            writeRuleSetFieldAudit(me, study, rs, "run_time",
                    oldRunTime == null ? "" : oldRunTime,
                    newRunTime == null ? "" : newRunTime);
        }

        LOG.info("Rule set schedule update: id={} study={} runSchedule={}->{} runTime='{}'->'{}' by={}",
                ruleSetId, study.getOid(),
                oldRunSchedule, newRunSchedule,
                oldRunTime == null ? "" : oldRunTime,
                newRunTime == null ? "" : newRunTime,
                me.getName());
        return ResponseEntity.ok(toDto(rs));
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/rule-sets/{id}/actions/{actionId}                      */
    /*   (Phase E RX.6 — per-action inline edit)                          */
    /* ----------------------------------------------------------------- */

    /**
     * Update one {@code rule_action} attached to a rule_set_rule under
     * the {@code rule_set} on the path. All fields are optional; a
     * {@code null} field leaves the persisted value alone, so the SPA
     * can submit a minimal patch covering only what the operator
     * actually edited.
     *
     * <p>Authorization: same gate as the RX.5 create — sysadmin OR
     * study director/coordinator bound to the active study, via
     * {@link StudyAdminAuthorization#roleMayEditStudy}.
     *
     * <h2>Scope cut</h2>
     *
     * <p>Only the four inline-supported action types
     * ({@code FILE_DISCREPANCY_NOTE} / {@code EMAIL} / {@code SHOW} /
     * {@code HIDE}) are editable here — the other four
     * ({@code INSERT}, {@code EVENT}, {@code NOTIFICATION},
     * {@code RANDOMIZE}) surface as 404 because their type-specific
     * editor surfaces aren't shipped yet (RX.8 territory).
     *
     * <p>Show/Hide {@code destinationProperty} edits are also out of
     * scope — the {@code rule_action_property} rows stay as set at
     * creation. Operators wanting different destinations delete +
     * recreate via the wizard.
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>404 if the rule_set isn't in the active study, the action
     *       isn't under the rule_set, or the action's type isn't one
     *       of the four inline-supported types.</li>
     *   <li>400 if {@code message} is present and either blank or
     *       &gt;{@value #MESSAGE_MAX_LENGTH} chars.</li>
     *   <li>400 if {@code to} is present (and the action is EMAIL)
     *       and either blank or fails the
     *       {@link #EMAIL_PATTERN} shape check.</li>
     *   <li>400 if {@code to} is present on a non-EMAIL action
     *       (mirrors the create endpoint's "to is for EMAIL only"
     *       contract).</li>
     *   <li>400 if {@code phaseGates} is present and no phase is
     *       enabled — mirrors {@code OCRERR_0050}.</li>
     * </ul>
     *
     * <h2>Audit</h2>
     *
     * <p>One {@code audit_log_event} row per actually-changed field.
     * {@code auditTable = "rule_action"}, {@code entityId = action.id},
     * {@code columnName ∈ {message, expression_evaluates_to, to,
     * run_administrative_data_entry, run_initial_data_entry,
     * run_double_data_entry, run_import_data_entry, run_batch}}.
     *
     * <p>Response shape: refreshed {@link RuleSetDto} (the full
     * parent rule_set, so the SPA can replace the row in
     * {@code rows} + {@code selected} in one swap).
     */
    @PutMapping("/{ruleSetId}/actions/{actionId}")
    @Transactional
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> updateAction(@PathVariable("ruleSetId") int ruleSetId,
                                          @PathVariable("actionId") int actionId,
                                          @RequestBody(required = false) UpdateRuleActionRequest body,
                                          HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule_action edit"));
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { message?, expressionEvaluatesTo?, to?, phaseGates? }"));
        }

        // Resolve scope: the rule_set must be in the active study;
        // the action must live under one of the rule_set_rule rows
        // attached to that rule_set; and the action type must be
        // one of the four inline-supported types.
        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }
        RuleActionBean targetAction = null;
        if (rs.getRuleSetRules() != null) {
            outer:
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                if (rsr.getActions() == null) continue;
                for (RuleActionBean a : rsr.getActions()) {
                    if (a.getId() != null && a.getId() == actionId) {
                        targetAction = a;
                        break outer;
                    }
                }
            }
        }
        if (targetAction == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No action with id " + actionId + " under rule set " + ruleSetId));
        }
        ActionType actionType = targetAction.getActionType();
        if (actionType == null || !isSupportedInlineActionType(actionType)) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Inline edit supports FILE_DISCREPANCY_NOTE / EMAIL / SHOW / HIDE only — "
                            + "action " + actionId + " is "
                            + (actionType == null ? "untyped" : actionType.name())));
        }

        List<Map<String, String>> errors = new ArrayList<>();

        // Message validation — required-shape, never null on the four
        // supported types so a blank submission is a real edit gesture
        // ("clear the message"). The legacy import path tolerates an
        // empty message; we mirror that and only reject overrun.
        String newMessage = null;
        if (body.message() != null) {
            newMessage = body.message().trim();
            if (newMessage.isEmpty()) {
                errors.add(Map.of("field", "message", "message", "message must not be blank"));
            } else if (newMessage.length() > MESSAGE_MAX_LENGTH) {
                errors.add(Map.of("field", "message", "message",
                        "message must be at most " + MESSAGE_MAX_LENGTH + " characters"));
            }
        }

        // To validation — only meaningful on EMAIL actions. A non-null
        // `to` on a SHOW/HIDE/DiscrepancyNote action is a client bug;
        // reject it explicitly rather than silently dropping.
        String newTo = null;
        if (body.to() != null) {
            if (actionType != ActionType.EMAIL) {
                errors.add(Map.of("field", "to", "message",
                        "to is only valid on EMAIL actions"));
            } else {
                newTo = body.to().trim();
                if (newTo.isEmpty()) {
                    errors.add(Map.of("field", "to", "message",
                            "to must not be blank on EMAIL actions"));
                } else if (!EMAIL_PATTERN.matcher(newTo).matches()) {
                    errors.add(Map.of("field", "to", "message",
                            "to must be a valid email address"));
                }
            }
        }

        // Phase gates — when submitted, at least one phase must be on.
        CreateRuleActionRequest.PhaseGatesInput gates = body.phaseGates();
        boolean newAdmin = false, newInitial = false, newDouble = false, newImport = false, newBatch = false;
        if (gates != null) {
            newAdmin = Boolean.TRUE.equals(gates.administrativeDataEntry());
            newInitial = Boolean.TRUE.equals(gates.initialDataEntry());
            newDouble = Boolean.TRUE.equals(gates.doubleDataEntry());
            newImport = Boolean.TRUE.equals(gates.importDataEntry());
            newBatch = Boolean.TRUE.equals(gates.batch());
            if (!(newAdmin || newInitial || newDouble || newImport || newBatch)) {
                errors.add(Map.of("field", "phaseGates", "message",
                        "At least one phase gate must be enabled"));
            }
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", errors));
        }

        // Per-field diff. The four message-bearing subtypes carry the
        // message on their own getter (single-table inheritance, but
        // per-class accessor). Use instanceof patterns so the compiler
        // tracks the subtype binding for the setter.
        String oldMessage = currentMessage(targetAction);
        String oldTo = actionType == ActionType.EMAIL && targetAction instanceof EmailActionBean ea
                ? (ea.getTo() == null ? "" : ea.getTo())
                : "";
        boolean oldEvalTo = Boolean.TRUE.equals(targetAction.getExpressionEvaluatesTo());
        boolean newEvalTo = body.expressionEvaluatesTo() == null
                ? oldEvalTo
                : Boolean.TRUE.equals(body.expressionEvaluatesTo());

        RuleActionRunBean run = targetAction.getRuleActionRun();
        boolean oldAdmin = run != null && Boolean.TRUE.equals(run.getAdministrativeDataEntry());
        boolean oldInitial = run != null && Boolean.TRUE.equals(run.getInitialDataEntry());
        boolean oldDouble = run != null && Boolean.TRUE.equals(run.getDoubleDataEntry());
        boolean oldImport = run != null && Boolean.TRUE.equals(run.getImportDataEntry());
        boolean oldBatch = run != null && Boolean.TRUE.equals(run.getBatch());

        boolean messageChanged = newMessage != null && !oldMessage.equals(newMessage);
        boolean toChanged = newTo != null && !oldTo.equals(newTo);
        boolean evalToChanged = body.expressionEvaluatesTo() != null && oldEvalTo != newEvalTo;
        boolean gatesChanged = gates != null && (
                oldAdmin != newAdmin
                        || oldInitial != newInitial
                        || oldDouble != newDouble
                        || oldImport != newImport
                        || oldBatch != newBatch);

        if (!messageChanged && !toChanged && !evalToChanged && !gatesChanged) {
            // SPA submitted the current state — return the refreshed
            // shape without writing.
            return ResponseEntity.ok(toDto(rs));
        }

        if (messageChanged) {
            applyMessage(targetAction, newMessage);
        }
        if (toChanged && targetAction instanceof EmailActionBean ea) {
            ea.setTo(newTo);
        }
        if (evalToChanged) {
            targetAction.setExpressionEvaluatesTo(newEvalTo);
        }
        if (gatesChanged) {
            if (run == null) {
                run = new RuleActionRunBean(newAdmin, newInitial, newDouble, newImport, newBatch);
                targetAction.setRuleActionRun(run);
            } else {
                run.setAdministrativeDataEntry(newAdmin);
                run.setInitialDataEntry(newInitial);
                run.setDoubleDataEntry(newDouble);
                run.setImportDataEntry(newImport);
                run.setBatch(newBatch);
            }
        }
        targetAction.setUpdater(me);
        targetAction.setUpdatedDate(new java.util.Date());

        // Save via the parent rule_set_rule's cascade — the action
        // rides on the same Hibernate session so the dirty-check
        // picks up our setters. We look up the parent rsr to call
        // saveOrUpdate on it; same pattern as createAction.
        RuleSetRuleBean parentRsr = null;
        for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
            if (rsr.getActions() != null && rsr.getActions().contains(targetAction)) {
                parentRsr = rsr;
                break;
            }
        }
        if (parentRsr != null) {
            ruleSetRuleDao.saveOrUpdate(parentRsr);
        } else {
            // Defensive — we found the action above so this branch
            // shouldn't trigger, but save the rule_set as a fallback
            // rather than silently losing the edit.
            ruleSetDao.saveOrUpdate(rs);
        }

        if (messageChanged) {
            writeRuleActionFieldAudit(me, study, targetAction, "message", oldMessage, newMessage);
        }
        if (toChanged) {
            writeRuleActionFieldAudit(me, study, targetAction, "to", oldTo, newTo);
        }
        if (evalToChanged) {
            writeRuleActionFieldAudit(me, study, targetAction, "expression_evaluates_to",
                    Boolean.toString(oldEvalTo), Boolean.toString(newEvalTo));
        }
        if (gatesChanged) {
            if (oldAdmin != newAdmin) {
                writeRuleActionFieldAudit(me, study, targetAction, "run_administrative_data_entry",
                        Boolean.toString(oldAdmin), Boolean.toString(newAdmin));
            }
            if (oldInitial != newInitial) {
                writeRuleActionFieldAudit(me, study, targetAction, "run_initial_data_entry",
                        Boolean.toString(oldInitial), Boolean.toString(newInitial));
            }
            if (oldDouble != newDouble) {
                writeRuleActionFieldAudit(me, study, targetAction, "run_double_data_entry",
                        Boolean.toString(oldDouble), Boolean.toString(newDouble));
            }
            if (oldImport != newImport) {
                writeRuleActionFieldAudit(me, study, targetAction, "run_import_data_entry",
                        Boolean.toString(oldImport), Boolean.toString(newImport));
            }
            if (oldBatch != newBatch) {
                writeRuleActionFieldAudit(me, study, targetAction, "run_batch",
                        Boolean.toString(oldBatch), Boolean.toString(newBatch));
            }
        }

        LOG.info("Update rule_action: id={} type={} rs={} study={} message={} to={} evalTo={} gates={} by={}",
                actionId, actionType, ruleSetId, study.getOid(),
                messageChanged, toChanged, evalToChanged, gatesChanged, me.getName());

        // Re-read the parent rule_set so the DTO reflects any cascade
        // changes (same pattern as createAction).
        RuleSetBean refreshed = ruleSetDao.findById(ruleSetId, study);
        return ResponseEntity.ok(toDto(refreshed == null ? rs : refreshed));
    }

    /**
     * Extract the {@code message} field for whichever of the four
     * inline-supported action subtypes is supplied. Returns empty
     * string for the base class — that's the historical no-message
     * action types (INSERT / EVENT / RANDOMIZE) which the caller has
     * already gated out via {@link #isSupportedInlineActionType}.
     */
    private static String currentMessage(RuleActionBean action) {
        if (action instanceof DiscrepancyNoteActionBean a) {
            return a.getMessage() == null ? "" : a.getMessage();
        }
        if (action instanceof EmailActionBean a) {
            return a.getMessage() == null ? "" : a.getMessage();
        }
        if (action instanceof ShowActionBean a) {
            return a.getMessage() == null ? "" : a.getMessage();
        }
        if (action instanceof HideActionBean a) {
            return a.getMessage() == null ? "" : a.getMessage();
        }
        return "";
    }

    /**
     * Apply a new message to whichever inline-supported subtype the
     * action is. Pair to {@link #currentMessage(RuleActionBean)}.
     */
    private static void applyMessage(RuleActionBean action, String message) {
        if (action instanceof DiscrepancyNoteActionBean a) {
            a.setMessage(message);
        } else if (action instanceof EmailActionBean a) {
            a.setMessage(message);
        } else if (action instanceof ShowActionBean a) {
            a.setMessage(message);
        } else if (action instanceof HideActionBean a) {
            a.setMessage(message);
        }
    }

    /**
     * Phase E RX.6 — one audit row per actually-changed scalar field
     * on a rule_action. Mirrors {@link #writeRuleSetFieldAudit} so
     * downstream audit-trail rendering treats rule, rule_set, and
     * rule_action diffs uniformly.
     */
    private void writeRuleActionFieldAudit(UserAccountBean me,
                                           StudyBean study,
                                           RuleActionBean action,
                                           String columnName,
                                           String oldValue,
                                           String newValue) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_action");
            ae.setEntityId(action.getId() == null ? 0 : action.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("rule_action_update: id=" + action.getId()
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_action field {} id={} (continuing): {}",
                    columnName, action.getId(), e.getMessage());
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rule-sets       — Phase E RX.5 inline rule_set create */
    /*                                                                    */
    /* The matching POST /api/v1/rules (rule-body create) lives on        */
    /* RuleExpressionApiController, which holds the /api/v1/rules path   */
    /* mapping. The two endpoints share the per-request ExpressionService */
    /* shape; co-locating them on one controller would force one to       */
    /* live under a fake path or drop the class-level @RequestMapping.   */
    /* ----------------------------------------------------------------- */

    /**
     * Create a new {@code rule_set} (target expression + scope) in
     * the active study and bind one or more existing rules to it via
     * {@code rule_set_rule} rows.
     *
     * <p>Mirrors the per-record chunk of
     * {@code RulesPostImportContainerService.validateRuleSetDefs} —
     * target expression syntax/scope check, scope-OID resolution
     * (SED / CRF / CRF version), rule-OID existence checks.
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>{@code target} — required. Validated via
     *       {@code ExpressionService.ruleSetExpressionChecker} bound
     *       to the active study scope.</li>
     *   <li>{@code studyEventDefinitionOid} — optional. If present,
     *       must resolve via {@link StudyEventDefinitionDAO#findByOid}.</li>
     *   <li>{@code crfOid} — optional. If present, must resolve via
     *       {@link CRFDAO#findByOid}.</li>
     *   <li>{@code crfVersionOid} — optional. If present, must
     *       resolve via {@link CRFVersionDAO#findByOid}, and if
     *       {@code crfOid} is also present the version's CRF id must
     *       match.</li>
     *   <li>{@code ruleOids[]} — required, non-empty. Each must
     *       resolve to an existing {@code rule} in the active study
     *       (via {@link RuleDao#findByOid(String, Integer)}).</li>
     * </ul>
     *
     * <p>Persistence: builds a {@link RuleSetBean} with its
     * {@link ExpressionBean} target + {@link RuleSetRuleBean} list,
     * sets {@code studyId} + the resolved scope ids + {@code status =
     * AVAILABLE}, and saves via {@code ruleSetDao.saveOrUpdate}.
     * Cascade on {@link RuleSetBean#getRuleSetRules()} persists the
     * {@code rule_set_rule} rows.
     *
     * <p>Audit: one {@code audit_log_event} row for the new rule_set
     * with {@code auditTable=rule_set}, {@code columnName=create},
     * {@code newValue=target}.
     */
    @PostMapping
    @Transactional
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> createRuleSet(@RequestBody(required = false) CreateRuleSetRequest body,
                                           HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule_set create"));
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { target, studyEventDefinitionOid?, crfOid?, crfVersionOid?, ruleOids: [...] }"));
        }

        List<Map<String, String>> errors = new ArrayList<>();
        String target = body.target() == null ? "" : body.target().trim();
        if (target.isEmpty()) {
            errors.add(Map.of("field", "target", "message", "target is required"));
        }
        List<String> ruleOids = body.ruleOids() == null ? List.of() : body.ruleOids();
        if (ruleOids.isEmpty()) {
            errors.add(Map.of("field", "ruleOids", "message", "ruleOids must contain at least one rule OID"));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", errors));
        }

        // Resolve scope OIDs (optional fields). Each unresolvable OID
        // emits a 400 with the offending field name.
        StudyEventDefinitionBean sedBean = null;
        if (body.studyEventDefinitionOid() != null && !body.studyEventDefinitionOid().isBlank()) {
            StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
            sedBean = sedDao.findByOid(body.studyEventDefinitionOid().trim());
            if (sedBean == null || sedBean.getId() <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "studyEventDefinitionOid",
                                "message", "No StudyEventDefinition with oid '"
                                        + body.studyEventDefinitionOid() + "'"))));
            }
        }

        CRFBean crfBean = null;
        if (body.crfOid() != null && !body.crfOid().isBlank()) {
            CRFDAO crfDao = new CRFDAO(dataSource);
            crfBean = crfDao.findByOid(body.crfOid().trim());
            if (crfBean == null || crfBean.getId() <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "crfOid",
                                "message", "No CRF with oid '" + body.crfOid() + "'"))));
            }
        }

        CRFVersionBean crfVersionBean = null;
        if (body.crfVersionOid() != null && !body.crfVersionOid().isBlank()) {
            CRFVersionDAO crfvDao = new CRFVersionDAO(dataSource);
            crfVersionBean = crfvDao.findByOid(body.crfVersionOid().trim());
            if (crfVersionBean == null || crfVersionBean.getId() <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "crfVersionOid",
                                "message", "No CRF version with oid '" + body.crfVersionOid() + "'"))));
            }
            if (crfBean != null && crfVersionBean.getCrfId() != crfBean.getId()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "crfVersionOid",
                                "message", "CRF version '" + body.crfVersionOid()
                                        + "' does not belong to CRF '" + body.crfOid() + "'"))));
            }
        }

        // Rule OID existence checks. Collect all unknown OIDs in one
        // pass so the operator sees them all rather than fixing one
        // typo at a time.
        List<String> missingRuleOids = new ArrayList<>();
        List<RuleBean> resolvedRules = new ArrayList<>(ruleOids.size());
        for (String ro : ruleOids) {
            if (ro == null || ro.isBlank()) continue;
            RuleBean rb = ruleDao.findByOid(ro.trim(), study.getId());
            if (rb == null) {
                missingRuleOids.add(ro);
            } else {
                resolvedRules.add(rb);
            }
        }
        if (!missingRuleOids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "ruleOids",
                            "message", "Unknown rule OID(s) in this study: "
                                    + String.join(", ", missingRuleOids)))));
        }
        if (resolvedRules.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "ruleOids",
                            "message", "ruleOids must contain at least one rule OID"))));
        }

        // Target syntax + scope validation. As per createRule, build a
        // per-request ExpressionObjectWrapper.
        ExpressionBean targetExpr = new ExpressionBean(Context.OC_RULES_V1, target);
        ExpressionObjectWrapper eow = new ExpressionObjectWrapper(
                dataSource, study, targetExpr, ExpressionObjectWrapper.CONTEXT_TARGET);
        ResourceBundleProvider.updateLocale(java.util.Locale.ENGLISH);
        ExpressionService perRequestExprSvc = new ExpressionService(eow);
        try {
            if (!perRequestExprSvc.ruleSetExpressionChecker(target)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "target",
                                "message", "Target expression failed validation"))));
            }
        } catch (OpenClinicaSystemException ose) {
            String code = ose.getErrorCode() == null ? "OCRERR_unknown" : ose.getErrorCode();
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "target",
                            "message", code + " : "
                                    + (ose.getMessage() == null ? "target invalid" : ose.getMessage())))));
        } catch (RuntimeException rte) {
            LOG.debug("createRuleSet: target checker threw {}", rte.toString());
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "target",
                            "message", rte.getMessage() == null ? "Target invalid" : rte.getMessage()))));
        }

        RuleSetBean rs = new RuleSetBean();
        rs.setOriginalTarget(targetExpr);
        rs.setStudy(study);
        rs.setStudyId(study.getId());
        if (sedBean != null) {
            rs.setStudyEventDefinition(sedBean);
        }
        if (crfBean != null) {
            rs.setCrf(crfBean);
        }
        if (crfVersionBean != null) {
            rs.setCrfVersion(crfVersionBean);
        }
        rs.setStatus(Status.AVAILABLE);
        rs.setOwner(me);
        rs.setCreatedDate(new java.util.Date());

        // Attach the rules. addRuleSetRule sets the back-reference
        // and the cascade on RuleSetBean.getRuleSetRules (ALL +
        // EAGER) persists each row when ruleSetDao.saveOrUpdate runs.
        for (RuleBean ruleBean : resolvedRules) {
            RuleSetRuleBean rsr = new RuleSetRuleBean();
            rsr.setRuleBean(ruleBean);
            rsr.setStatus(Status.AVAILABLE);
            rsr.setOwner(me);
            rsr.setCreatedDate(new java.util.Date());
            rs.addRuleSetRule(rsr);
        }

        RuleSetBean persisted = ruleSetDao.saveOrUpdate(rs);
        if (persisted == null || persisted.getId() == null || persisted.getId() == 0) {
            LOG.warn("createRuleSet: ruleSetDao.saveOrUpdate returned no id for target='{}'", target);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist rule set"));
        }

        writeRuleSetCreateAudit(me, study, persisted);
        LOG.info("Create rule set: id={} target='{}' study={} rules={} by={}",
                persisted.getId(), target, study.getOid(),
                resolvedRules.size(), me.getName());

        // Re-load to get the cascade-persisted rule_set_rule ids.
        RuleSetBean refreshed = ruleSetDao.findById(persisted.getId(), study);
        return ResponseEntity.status(201)
                .body(toDto(refreshed == null ? persisted : refreshed));
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/rule-sets/{id}/actions  — Phase E RX.5 action create  */
    /* ----------------------------------------------------------------- */

    /**
     * Attach one action to a {@code rule_set_rule} within the
     * rule_set on the path.
     *
     * <p>Scope cut: only {@code FILE_DISCREPANCY_NOTE},
     * {@code EMAIL}, {@code SHOW}, {@code HIDE} are creatable inline.
     * Other types ({@code INSERT}, {@code EVENT}, {@code NOTIFICATION},
     * {@code RANDOMIZE}) still require the XML import path —
     * surfacing them inline needs validators + UI affordances out of
     * scope for RX.5 (see scope analysis at
     * {@code docs/development/modernization/phase-e/rx-rules-scope.md}).
     *
     * <h2>Validation</h2>
     * <ul>
     *   <li>{@code ruleSetRuleId} — required. Must be one of the
     *       {@code rule_set_rule} rows under the rule_set in the path
     *       (cross-scope checked).</li>
     *   <li>{@code actionType} — required. Must be one of the four
     *       supported strings.</li>
     *   <li>{@code expressionEvaluatesTo} — optional, defaults to
     *       {@code false}.</li>
     *   <li>{@code message} — required for all four supported types
     *       (DiscrepancyNote / Email / Show / Hide); ≤2000 chars.</li>
     *   <li>{@code to} — required for EMAIL; basic
     *       {@link #EMAIL_PATTERN} shape check (the legacy validator
     *       is similarly loose).</li>
     *   <li>{@code properties[]} — optional for SHOW / HIDE. Each
     *       entry needs {@code oid} (OID-format) and exactly one of
     *       {@code value} or {@code valueExpression} (the latter is
     *       validated as a rule expression).</li>
     *   <li>{@code phaseGates} — required. At least one phase boolean
     *       must be true (mirrors {@code OCRERR_0050}).</li>
     * </ul>
     */
    @PostMapping("/{ruleSetId}/actions")
    @Transactional
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = RuleSetDto.class)))
    public ResponseEntity<?> createAction(@PathVariable("ruleSetId") int ruleSetId,
                                          @RequestBody(required = false) CreateRuleActionRequest body,
                                          HttpSession session) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule_action create"));
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body is required: { ruleSetRuleId, actionType, message?, to?, properties?, phaseGates }"));
        }

        List<Map<String, String>> errors = new ArrayList<>();
        Integer ruleSetRuleId = body.ruleSetRuleId();
        if (ruleSetRuleId == null || ruleSetRuleId <= 0) {
            errors.add(Map.of("field", "ruleSetRuleId", "message",
                    "ruleSetRuleId is required"));
        }
        String actionTypeRaw = body.actionType() == null ? "" : body.actionType().trim();
        ActionType actionType = null;
        if (actionTypeRaw.isEmpty()) {
            errors.add(Map.of("field", "actionType", "message", "actionType is required"));
        } else {
            try {
                actionType = ActionType.valueOf(actionTypeRaw);
            } catch (IllegalArgumentException iae) {
                errors.add(Map.of("field", "actionType", "message",
                        "Unknown actionType '" + actionTypeRaw + "'"));
            }
        }
        if (actionType != null && !isSupportedInlineActionType(actionType)) {
            errors.add(Map.of("field", "actionType", "message",
                    "Inline create supports FILE_DISCREPANCY_NOTE / EMAIL / SHOW / HIDE only — "
                            + actionTypeRaw + " requires the XML import path"));
        }
        if (body.phaseGates() == null) {
            errors.add(Map.of("field", "phaseGates", "message", "phaseGates is required"));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", errors));
        }
        // Past the error-list guard above actionType + gates are
        // guaranteed non-null. Local assertion silences the static
        // analyzer (and documents the invariant for future readers).
        final ActionType resolvedActionType = java.util.Objects.requireNonNull(actionType);

        // At least one phase must be enabled (mirrors OCRERR_0050).
        CreateRuleActionRequest.PhaseGatesInput gates = body.phaseGates();
        boolean adminGate = Boolean.TRUE.equals(gates.administrativeDataEntry());
        boolean initialGate = Boolean.TRUE.equals(gates.initialDataEntry());
        boolean doubleGate = Boolean.TRUE.equals(gates.doubleDataEntry());
        boolean importGate = Boolean.TRUE.equals(gates.importDataEntry());
        boolean batchGate = Boolean.TRUE.equals(gates.batch());
        if (!(adminGate || initialGate || doubleGate || importGate || batchGate)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "phaseGates",
                            "message", "At least one phase gate must be enabled"))));
        }

        // Type-specific field validation. message is required for
        // all four supported types; to is required for EMAIL.
        String message = body.message() == null ? "" : body.message().trim();
        if (message.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "message",
                            "message", "message is required for "
                                    + resolvedActionType.name()))));
        }
        if (message.length() > MESSAGE_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "message",
                            "message", "message must be at most "
                                    + MESSAGE_MAX_LENGTH + " characters"))));
        }

        String to = body.to() == null ? "" : body.to().trim();
        if (resolvedActionType == ActionType.EMAIL) {
            if (to.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "to",
                                "message", "to is required for EMAIL actions"))));
            }
            if (!EMAIL_PATTERN.matcher(to).matches()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "to",
                                "message", "to must be a valid email address"))));
            }
        }

        // Properties: optional for SHOW / HIDE; each entry needs an
        // OID-format string and exactly one of value / valueExpression.
        List<CreateRuleActionRequest.PropertyInput> propInputs =
                body.properties() == null ? List.of() : body.properties();
        if (!propInputs.isEmpty() && resolvedActionType != ActionType.SHOW
                && resolvedActionType != ActionType.HIDE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed",
                    "errors", List.of(Map.of("field", "properties",
                            "message", "properties only apply to SHOW / HIDE actions"))));
        }
        for (CreateRuleActionRequest.PropertyInput pi : propInputs) {
            String propOid = pi.oid() == null ? "" : pi.oid().trim();
            if (propOid.isEmpty() || !RULE_OID_PATTERN.matcher(propOid).matches()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "properties.oid",
                                "message", "Each property needs an OID-format oid"))));
            }
            boolean hasValue = pi.value() != null && !pi.value().isEmpty();
            boolean hasValueExpr = pi.valueExpression() != null && !pi.valueExpression().isBlank();
            if (hasValue == hasValueExpr) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "properties",
                                "message", "Each property needs exactly one of value or valueExpression (oid="
                                        + propOid + ")"))));
            }
        }

        // Resolve scope. The rule_set must be in the active study;
        // the rule_set_rule must live under that rule_set.
        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }
        RuleSetRuleBean targetRsr = null;
        if (rs.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                if (rsr.getId() != null && rsr.getId().equals(ruleSetRuleId)) {
                    targetRsr = rsr;
                    break;
                }
            }
        }
        if (targetRsr == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No attached rule with id " + ruleSetRuleId
                            + " under rule set " + ruleSetId));
        }

        // Validate property value-expressions against the study scope
        // — same checker as the rule body. Bailing here keeps the
        // user-visible "your expression doesn't resolve" message
        // alongside the rest of the validation.
        for (CreateRuleActionRequest.PropertyInput pi : propInputs) {
            if (pi.valueExpression() == null || pi.valueExpression().isBlank()) continue;
            ExpressionBean ve = new ExpressionBean(Context.OC_RULES_V1, pi.valueExpression().trim());
            ExpressionObjectWrapper eow = new ExpressionObjectWrapper(
                    dataSource, study, ve, rs, ExpressionObjectWrapper.CONTEXT_EXPRESSION);
            ResourceBundleProvider.updateLocale(java.util.Locale.ENGLISH);
            ExpressionService perRequestExprSvc = new ExpressionService(eow);
            try {
                if (!perRequestExprSvc.ruleExpressionChecker(pi.valueExpression().trim())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "Validation failed",
                            "errors", List.of(Map.of("field", "properties.valueExpression",
                                    "message", "valueExpression failed validation (oid="
                                            + pi.oid() + ")"))));
                }
            } catch (OpenClinicaSystemException ose) {
                String code = ose.getErrorCode() == null ? "OCRERR_unknown" : ose.getErrorCode();
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "properties.valueExpression",
                                "message", code + " : "
                                        + (ose.getMessage() == null ? "valueExpression invalid" : ose.getMessage())))));
            } catch (RuntimeException rte) {
                LOG.debug("createAction: valueExpression checker threw {}", rte.toString());
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed",
                        "errors", List.of(Map.of("field", "properties.valueExpression",
                                "message", rte.getMessage() == null ? "valueExpression invalid"
                                        : rte.getMessage()))));
            }
        }

        // Build the right action subtype.
        RuleActionBean action;
        switch (resolvedActionType) {
            case FILE_DISCREPANCY_NOTE -> {
                DiscrepancyNoteActionBean a = new DiscrepancyNoteActionBean();
                a.setMessage(message);
                action = a;
            }
            case EMAIL -> {
                EmailActionBean a = new EmailActionBean();
                a.setMessage(message);
                a.setTo(to);
                action = a;
            }
            case SHOW -> {
                ShowActionBean a = new ShowActionBean();
                a.setMessage(message);
                a.setProperties(buildPropertyBeans(propInputs));
                action = a;
            }
            case HIDE -> {
                HideActionBean a = new HideActionBean();
                a.setMessage(message);
                a.setProperties(buildPropertyBeans(propInputs));
                action = a;
            }
            default ->
                    // Already gated above; unreachable but keeps the
                    // compiler happy on the exhaustive-switch shape.
                    throw new IllegalStateException("Unsupported actionType "
                            + resolvedActionType);
        }
        action.setExpressionEvaluatesTo(Boolean.TRUE.equals(body.expressionEvaluatesTo()));

        // Overwrite the subtype-constructor's default RuleActionRunBean
        // with the operator's submitted phase gates. The legacy
        // subtype constructors prefill a sensible default but the
        // controller's contract is "operator picks gates explicitly"
        // — so a freshly-built run bean is what we persist.
        action.setRuleActionRun(new RuleActionRunBean(
                adminGate, initialGate, doubleGate, importGate, batchGate));

        // Attach to the rule_set_rule's actions list and save via the
        // parent's cascade. The action's owner / created-date come
        // from the controller (AbstractAuditableMutableDomainObject
        // tracks these).
        action.setOwner(me);
        action.setCreatedDate(new java.util.Date());

        if (targetRsr.getActions() == null) {
            targetRsr.setActions(new ArrayList<>());
        }
        targetRsr.getActions().add(action);

        ruleSetRuleDao.saveOrUpdate(targetRsr);

        // Re-read the parent rule_set so the DTO carries the newly
        // attached action with its persisted id.
        RuleSetBean refreshed = ruleSetDao.findById(ruleSetId, study);
        Integer newActionId = null;
        if (refreshed != null && refreshed.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : refreshed.getRuleSetRules()) {
                if (rsr.getId() != null && rsr.getId().equals(ruleSetRuleId)) {
                    if (rsr.getActions() != null && !rsr.getActions().isEmpty()) {
                        // The newest action sits at the end of the
                        // cascade-persisted list (Hibernate preserves
                        // insertion order for the SUBSELECT fetch).
                        RuleActionBean last = rsr.getActions().get(rsr.getActions().size() - 1);
                        newActionId = last.getId();
                    }
                }
            }
        }
        writeRuleActionCreateAudit(me, study,
                newActionId == null ? 0 : newActionId, resolvedActionType);
        LOG.info("Create rule action: type={} rsr={} rs={} study={} by={}",
                resolvedActionType, ruleSetRuleId, ruleSetId, study.getOid(), me.getName());

        return ResponseEntity.status(201)
                .body(toDto(refreshed == null ? rs : refreshed));
    }

    /* ----------------------------------------------------------------- */
    /* Helpers — Phase E RX.5 create endpoints                            */
    /* ----------------------------------------------------------------- */

    private static boolean isSupportedInlineActionType(ActionType t) {
        return t == ActionType.FILE_DISCREPANCY_NOTE
                || t == ActionType.EMAIL
                || t == ActionType.SHOW
                || t == ActionType.HIDE;
    }

    private static List<PropertyBean> buildPropertyBeans(
            List<CreateRuleActionRequest.PropertyInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return new ArrayList<>();
        List<PropertyBean> out = new ArrayList<>(inputs.size());
        for (CreateRuleActionRequest.PropertyInput pi : inputs) {
            PropertyBean pb = new PropertyBean();
            pb.setOid(pi.oid().trim());
            if (pi.value() != null && !pi.value().isEmpty()) {
                pb.setValue(pi.value());
            }
            if (pi.valueExpression() != null && !pi.valueExpression().isBlank()) {
                pb.setValueExpression(new ExpressionBean(
                        Context.OC_RULES_V1, pi.valueExpression().trim()));
            }
            out.add(pb);
        }
        return out;
    }

    private void writeRuleCreateAudit(UserAccountBean me, StudyBean study, RuleBean rule) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule");
            ae.setEntityId(rule.getId());
            ae.setColumnName("create");
            ae.setOldValue("");
            ae.setNewValue(rule.getOid() == null ? "" : rule.getOid());
            ae.setActionMessage("rule_create: oid=" + rule.getOid()
                    + " name='" + (rule.getName() == null ? "" : rule.getName())
                    + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_create id={} (continuing): {}",
                    rule.getId(), e.getMessage());
        }
    }

    private void writeRuleSetCreateAudit(UserAccountBean me, StudyBean study, RuleSetBean rs) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_set");
            ae.setEntityId(rs.getId());
            ae.setColumnName("create");
            ae.setOldValue("");
            String targetValue = rs.getTarget() == null ? "" : rs.getTarget().getValue();
            ae.setNewValue(targetValue == null ? "" : targetValue);
            ae.setActionMessage("rule_set_create: id=" + rs.getId()
                    + " target='" + (targetValue == null ? "" : targetValue)
                    + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_set_create id={} (continuing): {}",
                    rs.getId(), e.getMessage());
        }
    }

    private void writeRuleActionCreateAudit(UserAccountBean me, StudyBean study,
                                            int newActionId, ActionType actionType) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_action");
            ae.setEntityId(newActionId);
            ae.setColumnName("create");
            ae.setOldValue("");
            ae.setNewValue(actionType == null ? "" : actionType.name());
            ae.setActionMessage("rule_action_create: id=" + newActionId
                    + " type=" + (actionType == null ? "?" : actionType.name())
                    + " by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_action_create id={} (continuing): {}",
                    newActionId, e.getMessage());
        }
    }

    /* ----------------------------------------------------------------- */
    /* Lifecycle helpers                                                  */
    /* ----------------------------------------------------------------- */

    private ResponseEntity<?> ruleSetLifecycle(int ruleSetId,
                                               HttpSession session,
                                               Status targetStatus,
                                               String operation) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule_set " + operation));
        }

        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }

        Status oldStatus = rs.getStatus();
        if (oldStatus != null && oldStatus.equals(targetStatus)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Rule set " + ruleSetId + " is already "
                            + targetStatus.getName().toLowerCase()));
        }

        rs.setStatus(targetStatus);
        rs.setUpdater(me);
        rs.setUpdatedDate(new java.util.Date());
        ruleSetDao.saveOrUpdate(rs);

        writeRuleSetAudit(me, study, rs, oldStatus, targetStatus, operation);

        LOG.info("Rule set {}: id={} study={} by={}",
                operation, ruleSetId, study.getOid(), me.getName());
        return ResponseEntity.ok(toDto(rs));
    }

    private ResponseEntity<?> attachedRuleLifecycle(int ruleSetId,
                                                    int ruleSetRuleId,
                                                    HttpSession session,
                                                    Status targetStatus,
                                                    String operation) {
        ResponseEntity<?> guard = preflight(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!StudyAdminAuthorization.roleMayEditStudy(me, currentRole, study)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit rule " + operation));
        }

        RuleSetBean rs = ruleSetDao.findById(ruleSetId, study);
        if (rs == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No rule set with id " + ruleSetId + " in study '" + study.getOid() + "'"));
        }

        // Walk the parent's rule_set_rule list and match by id so we
        // simultaneously confirm scope (the binding belongs to this
        // rule_set, not a sibling). Cheaper than a separate DAO call
        // + scope check given the list is already loaded by
        // findById's HQL.
        RuleSetRuleBean target = null;
        if (rs.getRuleSetRules() != null) {
            for (RuleSetRuleBean rsr : rs.getRuleSetRules()) {
                if (rsr.getId() != null && rsr.getId() == ruleSetRuleId) {
                    target = rsr;
                    break;
                }
            }
        }
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No attached rule with id " + ruleSetRuleId
                            + " under rule set " + ruleSetId));
        }

        Status oldStatus = target.getStatus();
        if (oldStatus != null && oldStatus.equals(targetStatus)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Attached rule " + ruleSetRuleId + " is already "
                            + targetStatus.getName().toLowerCase()));
        }

        target.setStatus(targetStatus);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        ruleSetRuleDao.saveOrUpdate(target);

        writeRuleSetRuleAudit(me, study, target, oldStatus, targetStatus, operation);

        LOG.info("Attached rule {}: id={} rule_set={} study={} by={}",
                operation, ruleSetRuleId, ruleSetId, study.getOid(), me.getName());

        // Re-read the parent so the returned DTO reflects the flipped
        // child status (Hibernate's identity map keeps the in-session
        // bean in sync, but a fresh findById also flushes the change
        // back through the projection helpers without surprises).
        RuleSetBean refreshed = ruleSetDao.findById(ruleSetId, study);
        return ResponseEntity.ok(toDto(refreshed == null ? rs : refreshed));
    }

    private void writeRuleSetAudit(UserAccountBean me,
                                   StudyBean study,
                                   RuleSetBean rs,
                                   Status oldStatus,
                                   Status newStatus,
                                   String operation) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_set");
            ae.setEntityId(rs.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getCode()));
            ae.setNewValue(String.valueOf(newStatus.getCode()));
            ae.setActionMessage("rule_set_" + operation + ": id=" + rs.getId()
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + newStatus.getName() + ") by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_set_{} id={} (continuing): {}",
                    operation, rs.getId(), e.getMessage());
        }
    }

    /**
     * Phase E RX.7 — one audit row per changed scalar field on a
     * rule_set. Used by {@link #setSchedule} which can emit 0, 1, or
     * 2 rows depending on which fields actually changed.
     */
    private void writeRuleSetFieldAudit(UserAccountBean me,
                                        StudyBean study,
                                        RuleSetBean rs,
                                        String columnName,
                                        String oldValue,
                                        String newValue) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_set");
            ae.setEntityId(rs.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("rule_set_schedule_update: id=" + rs.getId()
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "' by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_set field {}={} (continuing): {}",
                    columnName, newValue, e.getMessage());
        }
    }

    private void writeRuleSetRuleAudit(UserAccountBean me,
                                       StudyBean study,
                                       RuleSetRuleBean rsr,
                                       Status oldStatus,
                                       Status newStatus,
                                       String operation) {
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setStudyId(study.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setAuditTable("rule_set_rule");
            ae.setEntityId(rsr.getId());
            ae.setColumnName("status_id");
            ae.setOldValue(oldStatus == null ? "" : String.valueOf(oldStatus.getCode()));
            ae.setNewValue(String.valueOf(newStatus.getCode()));
            ae.setActionMessage("rule_set_rule_" + operation + ": id=" + rsr.getId()
                    + " (" + (oldStatus == null ? "?" : oldStatus.getName())
                    + " → " + newStatus.getName() + ") by " + me.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for rule_set_rule_{} id={} (continuing): {}",
                    operation, rsr.getId(), e.getMessage());
        }
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
