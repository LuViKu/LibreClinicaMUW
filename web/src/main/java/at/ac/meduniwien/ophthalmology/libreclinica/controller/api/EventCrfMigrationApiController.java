/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.EventCrfVersionMigrationService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.EventCrfVersionMigrationService.Refusal;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moving existing event CRFs to another version of their CRF: the SPA's
 * batch CRF version migration. See {@link EventCrfVersionMigrationService}
 * for what is moved and changed, and how it relates to the legacy
 * {@code BatchCRFMigrationController} and {@code ChangeCRFVersionController}.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/crfs/{crfOid}/event-crf-migration/options?studyOid=}
 *       — versions, sites and event definitions to choose from</li>
 *   <li>{@code POST /api/v1/crfs/{crfOid}/event-crf-migration/preview} — what
 *       a run would move and clear; writes nothing</li>
 *   <li>{@code POST /api/v1/crfs/{crfOid}/event-crf-migration} — the run;
 *       one transaction, returns the log</li>
 * </ul>
 *
 * <p>This is not {@code POST /crfs/{oid}/versions/{from}/migrate-to/{to}}
 * on {@link CrfsApiController}, which changes only the version that new
 * event CRFs get and moves no data.
 *
 * <p>Refusals: 401 unauthenticated; 403 unless the caller is Data Manager
 * or CRC of the study; 404 unknown CRF; 400 with field errors for an invalid
 * selection; 409 for a removed CRF, a study that is not available, or (run
 * only) a selection that no longer matches its preview.
 */
@RestController
@RequestMapping("/api/v1/crfs/{crfOid}/event-crf-migration")
@Tag(name = "CRFs", description = "CRF library + version upload (build-study surface).")
public class EventCrfMigrationApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventCrfMigrationApiController.class);

    private final DataSource dataSource;
    private final EventCrfVersionMigrationService migrationService;

    @Autowired
    public EventCrfMigrationApiController(@Qualifier("dataSource") DataSource dataSource,
                                          EventCrfVersionMigrationService migrationService) {
        this.dataSource = dataSource;
        this.migrationService = migrationService;
    }

    @GetMapping("/options")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfMigrationDto.Options.class)))
    public ResponseEntity<?> options(@PathVariable("crfOid") String crfOid,
                                     @RequestParam(value = "studyOid", required = false) String studyOid,
                                     HttpSession session) {
        UserAccountBean me = currentUser(session);
        if (me == null) return unauthenticated();
        CRFBean crf = crf(crfOid);
        if (crf == null) return notFound(crfOid);
        try {
            return ResponseEntity.ok(migrationService.options(crf, studyOid, me));
        } catch (Refusal r) {
            return refused(r);
        }
    }

    @PostMapping("/preview")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfMigrationDto.Preview.class)))
    public ResponseEntity<?> preview(@PathVariable("crfOid") String crfOid,
                                     @RequestBody(required = false) EventCrfMigrationDto.Request body,
                                     HttpSession session) {
        UserAccountBean me = currentUser(session);
        if (me == null) return unauthenticated();
        CRFBean crf = crf(crfOid);
        if (crf == null) return notFound(crfOid);
        try {
            return ResponseEntity.ok(migrationService.preview(crf, body, me));
        } catch (Refusal r) {
            return refused(r);
        }
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfMigrationDto.Result.class)))
    public ResponseEntity<?> run(@PathVariable("crfOid") String crfOid,
                                 @RequestBody(required = false) EventCrfMigrationDto.Request body,
                                 HttpSession session) {
        UserAccountBean me = currentUser(session);
        if (me == null) return unauthenticated();
        CRFBean crf = crf(crfOid);
        if (crf == null) return notFound(crfOid);
        try {
            return ResponseEntity.ok(migrationService.run(crf, body, me));
        } catch (Refusal r) {
            return refused(r);
        } catch (IllegalStateException e) {
            LOG.error("Event CRF migration failed for crf={} by user={}", crfOid, me.getName(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Moving the event CRFs failed; nothing was changed."));
        }
    }

    private static UserAccountBean currentUser(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        return me == null || me.getId() == 0 ? null : me;
    }

    private CRFBean crf(String crfOid) {
        CRFBean crf = new CRFDAO(dataSource).findByOid(crfOid);
        return crf == null || crf.getId() == 0 ? null : crf;
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
    }

    private static ResponseEntity<?> notFound(String crfOid) {
        return ResponseEntity.status(404).body(Map.of("message", "No CRF with oid '" + crfOid + "'"));
    }

    private static ResponseEntity<?> refused(Refusal r) {
        if (!r.errors().isEmpty()) {
            return ResponseEntity.status(r.status()).body(new ValidationErrorBody(r.getMessage(), r.errors()));
        }
        return ResponseEntity.status(r.status()).body(Map.of("message", r.getMessage()));
    }
}
