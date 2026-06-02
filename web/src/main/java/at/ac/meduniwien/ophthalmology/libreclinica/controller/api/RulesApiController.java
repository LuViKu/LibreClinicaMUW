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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
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

    @Autowired
    public RulesApiController(@Qualifier("dataSource") DataSource dataSource,
                              RuleSetDao ruleSetDao,
                              RuleSetRuleDao ruleSetRuleDao,
                              RuleActionRunLogDao ruleActionRunLogDao) {
        this.dataSource = dataSource;
        this.ruleSetDao = ruleSetDao;
        this.ruleSetRuleDao = ruleSetRuleDao;
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
