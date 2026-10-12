/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.core.ClinicZone;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-025 — Modality Worklist source for the {@code dicom-scp} sidecar.
 *
 * <p>The Optomed Lumo's standard-DICOM integration is worklist-driven: the
 * camera pulls a Modality Worklist (C-FIND) to pick the scheduled patient/exam,
 * then C-STOREs the study carrying that identity. The sidecar answers the
 * C-FIND from the entries served here — LibreClinica's <em>scheduled</em> visits
 * ({@code study_event}, status scheduled / data-entry-started) in a date window.
 * The sidecar stamps each item with the accession {@code LC<study_event_id>} and
 * a deterministic StudyInstanceUID; {@link DicomIngestApiController} parses the
 * accession back and auto-binds the returning study to that visit.
 *
 * <p>Identity is the EDC's pseudonymised subject label — the EDC holds no real
 * names. Not a browser endpoint: whitelisted {@code permitAll} and gated by the
 * shared-secret {@code X-MUW-Dicom-Token} ({@code core.dicom.ingest.token}); the
 * reverse proxy must never expose it. Nothing patient-identifying is logged.
 *
 * <p>DR-040 phase 3 — the camera's place. The sidecar passes the calling AE
 * title ({@code callingAe}); the title is a {@code dicom_ae} ingest source
 * (made on first sight, as the image hand-off does, so it shows on System →
 * Standorte → Quellen), and the worklist lists only the visits of studies at
 * that source's location, within {@code core.dicom.worklist.studyOids}. A
 * camera without a place falls back to the default location: in single mode
 * the one location, so nothing changes; in multi mode none, and the camera is
 * shown no schedule until an administrator assigns it. An older sidecar that
 * sends no title is served as before in single mode and refused (400) in
 * multi mode, which could not say whose schedule to show.
 */
@RestController
@RequestMapping("/api/v1/internal/dicom-worklist")
@Tag(name = "DICOM worklist (internal)",
     description = "Scheduled visits as Modality Worklist entries for the dicom-scp sidecar.")
public class DicomWorklistApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DicomWorklistApiController.class);

    private static final String TOKEN_HEADER = "X-MUW-Dicom-Token";
    /** Widest window a single query may span — a wildcard C-FIND can't dump the schedule. */
    private static final int MAX_WINDOW_DAYS = 31;
    /** Hard row cap — a 31-day window on a busy study is still one response. */
    private static final int MAX_ENTRIES = 2000;
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HHmmss");

    private final DataSource dataSource;
    private final IngestSourceService sources;

    @Autowired
    public DicomWorklistApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.sources = new IngestSourceService(dataSource);
    }

    /** One worklist entry — the sidecar maps this onto a PS3.4 K.6 MWL item. */
    public record WorklistEntry(int studyEventId, String subjectLabel, String gender,
                                String dateOfBirth, String date, String time,
                                String eventLabel, String studyName, String modality) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> worklist(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "callingAe", required = false) String callingAe,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        String expected = cfg("core.dicom.ingest.token", "");
        if (expected.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("message", "DICOM ingest token not configured"));
        }
        if (token == null || !constantTimeEquals(token, expected)) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or missing " + TOKEN_HEADER));
        }

        LocalDate d0 = parseIsoDateOrNull(from);
        if (d0 == null) d0 = ClinicZone.today();
        LocalDate d1 = parseIsoDateOrNull(to);
        if (d1 == null) d1 = d0;
        if (d1.isBefore(d0)) {
            LocalDate t = d0; d0 = d1; d1 = t;
        }
        if (d1.isAfter(d0.plusDays(MAX_WINDOW_DAYS))) {
            d1 = d0.plusDays(MAX_WINDOW_DAYS);
        }

        // 2026-09-18 — restrict to the studies this device may see.
        //
        // A worklist hands the camera subject labels, sex and dates of birth
        // for every visit in the window. A handheld on a clinic bench is a
        // shared device, so without this a camera enrolled for HealthAEye also
        // displays the nAMD study's schedule. `core.dicom.worklist.studyOids`
        // names what it may offer; blank keeps the previous unrestricted
        // behaviour for single-study dev instances.
        //
        // DR-040 phase 3 — and to the camera's place (see the class comment).
        java.util.Set<Integer> allowed;
        String place;
        String ae = callingAe == null ? "" : callingAe.trim();
        if (ae.isEmpty()) {
            if (LocationMode.current() == LocationMode.MULTI) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "callingAe is required in multi-location mode: update the dicom-scp sidecar"));
            }
            allowed = StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.WORKLIST_KEY);
            place = "no calling AE title";
        } else {
            IngestSourceService.Source source = sourceOf(ae);
            Integer location = source == null ? null : source.locationId();
            allowed = DeviceScope.studiesAtPlace(dataSource, StudyScopeConfig.WORKLIST_KEY, location);
            place = "AE '" + ae + "' at " + (location != null ? "location id " + location
                    : source == null ? "no source, the default location" : "no location, the default location");
        }
        String studyScope = StudyScopeConfig.inClauseOrNull(allowed);

        // One query, shared with the upload page's visit picker — the two must
        // never disagree about which visits are open. See ScheduledVisitQuery.
        List<WorklistEntry> entries = new ArrayList<>();
        try {
            for (ScheduledVisitQuery.ScheduledVisit v :
                    ScheduledVisitQuery.query(dataSource, d0, d1, allowed, MAX_ENTRIES)) {
                entries.add(new WorklistEntry(
                        v.studyEventId(),
                        v.subjectLabel(),
                        v.gender(),
                        v.dateOfBirth(),
                        v.date(),
                        // DICOM TM, which is what the sidecar puts on the wire.
                        v.time() == null ? null : v.time().format(TM),
                        v.eventLabel(),
                        v.studyName(),
                        "OP"));
            }
        } catch (SQLException e) {
            LOG.error("DICOM worklist query failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "worklist query failed"));
        }
        // The scope is logged because an unset key on a multi-study instance
        // means the camera is being shown every study's schedule.
        LOG.info("DICOM worklist served: {} entries for {}..{} to {} (study scope: {})",
                entries.size(), d0, d1, place, studyScope == null ? "ALL STUDIES" : studyScope);
        return ResponseEntity.ok(Map.of("from", d0.toString(), "to", d1.toString(), "entries", entries));
    }

    /**
     * The calling AE title's source, made on first sight like the image
     * hand-off's ({@link DicomIngestApiController#MAX_AE_SOURCES} at most), or
     * null when the title cannot be one.
     */
    private IngestSourceService.Source sourceOf(String ae) {
        try {
            return sources.resolveAutoSource(IngestSourceService.KIND_DICOM_AE, ae, ae,
                    DicomIngestApiController.MAX_AE_SOURCES).orElse(null);
        } catch (IngestSourceService.Refusal notATitle) {
            return null;
        } catch (IngestSourceService.Failure unreadable) {
            LOG.warn("DICOM worklist: the calling AE title's source could not be read ({})", unreadable.sqlState());
            return null;
        }
    }

    private static LocalDate parseIsoDateOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String cfg(String key, String dflt) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable — use the default.
        }
        return dflt;
    }
}
