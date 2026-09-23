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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Today's scheduled visits as the Optomed Client's worklist file.
 *
 * <p>The camera-side story is in {@link OptomedWorklistFormat}. This is the
 * server side: a scheduled task on the clinic PC fetches this every few
 * minutes and drops the body into the Client's watched folder, because the
 * photographer enrols subjects during clinic and a list pulled once in the
 * morning would not have them.
 *
 * <p><strong>Why this is not the DICOM worklist endpoint with a different
 * content type.</strong> {@code /api/v1/internal/dicom-worklist} is refused
 * at the reverse proxy — deliberately: it hands out subject labels, sex and
 * real dates of birth behind nothing but a shared secret, and a secret in a
 * camera's config screen is not a credential anyone rotates. A clinic PC has
 * to reach this one through that proxy, so it earns the exposure by carrying
 * less and asking more:
 *
 * <ul>
 *   <li>no real date of birth — a placeholder, so a leaked file plus a label
 *       re-identifies nobody;</li>
 *   <li>its own token ({@code core.optomed.worklist.token}), so the camera's
 *       DICOM secret does not open this and this does not open that;</li>
 *   <li>off unless {@code core.optomed.worklist.enabled=true}, and the path
 *       answers 404 while off so it does not announce itself — the same
 *       posture as the public page's day-list, which is also a list of the
 *       day's patients and is also off by default;</li>
 *   <li>the same per-device study scope as the DICOM worklist
 *       ({@code core.dicom.worklist.studyOids}), so a handheld enrolled for
 *       one study is not shown another's schedule;</li>
 *   <li>an optional per-host allow-list at nginx, for the clinic PC's address.</li>
 * </ul>
 *
 * <p>Same query as the DICOM worklist and the upload page's visit picker
 * ({@link ScheduledVisitQuery}), so the three never disagree about which
 * visits are open. Nothing patient-identifying is logged — counts only.
 */
@RestController
@RequestMapping("/api/v1/device/optomed")
@Tag(name = "Optomed Client worklist (device)",
     description = "Scheduled visits as the Optomed Client's worklist_optomed_lumo.txt, for the USB-docked Lumo.")
public class OptomedWorklistApiController {

    private static final Logger LOG = LoggerFactory.getLogger(OptomedWorklistApiController.class);

    static final String TOKEN_HEADER = "X-MUW-Optomed-Token";
    static final String ENABLED_KEY = "core.optomed.worklist.enabled";
    static final String TOKEN_KEY = "core.optomed.worklist.token";

    /**
     * One day's clinic is tens of visits; the DICOM worklist caps a 31-day
     * window at a few hundred. This is a single day and the camera screen
     * scrolls badly, so the cap is the same order and exists so a
     * misconfigured scope cannot turn the file into the whole study.
     */
    static final int MAX_ENTRIES = 500;

    private static final MediaType TEXT_ASCII = new MediaType("text", "plain", StandardCharsets.US_ASCII);

    /**
     * Where a configuration value comes from. In production that is
     * {@code CoreResources}; in a unit test it is a map.
     *
     * <p>Injected so the gate can be unit-tested without a configured
     * {@code CoreResources}: with a map behind it, the 404, 503 and 401
     * branches are all exercised deterministically. (It was introduced while
     * chasing a CI 500 that turned out to be the mapping's {@code produces},
     * not configuration at all — see the comment on {@link #worklist}. The
     * injection stayed because it is the right shape regardless.)
     */
    @FunctionalInterface
    interface ConfigReader {
        /** The raw value, or null when the key is absent or unreadable. */
        String get(String key);
    }

    private final DataSource dataSource;
    private final ConfigReader config;

    @Autowired
    public OptomedWorklistApiController(@Qualifier("dataSource") DataSource dataSource) {
        this(dataSource, OptomedWorklistApiController::coreResourcesField);
    }

    OptomedWorklistApiController(DataSource dataSource, ConfigReader config) {
        this.dataSource = dataSource;
        this.config = config;
    }

    /** The production reader: {@code datainfo.properties} via CoreResources. */
    private static String coreResourcesField(String key) {
        try {
            return CoreResources.getField(key);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * @param date the clinic day, ISO {@code yyyy-MM-dd}; today when absent.
     *             One day only — the camera's list is what the photographer
     *             picks from, and a week of visits is a longer scroll, not a
     *             better list.
     */
    // No `produces` here, on purpose. The file itself goes out as text/plain,
    // set explicitly on the success response below; the 404/503/401 bodies
    // are JSON maps like every other endpoint's. With produces=text/plain on
    // the mapping there was no converter able to write a Map as text/plain,
    // so every refusal became a 500 - on every host, which is what CI showed
    // twice before the cause was read correctly.
    @GetMapping(value = "/worklist.txt")
    public ResponseEntity<?> worklist(
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        // Off means invisible: a 404 tells a prober nothing a 403 would not,
        // and tells them less than a 401 would.
        if (!"true".equalsIgnoreCase(cfg(ENABLED_KEY, "false"))) {
            return ResponseEntity.status(404).body(Map.of("message", "not found"));
        }
        String expected = cfg(TOKEN_KEY, "");
        if (expected.isBlank()) {
            LOG.warn("Optomed worklist requested but {} is not configured", TOKEN_KEY);
            return ResponseEntity.status(503).body(Map.of("message", "Optomed worklist token not configured"));
        }
        if (token == null || !constantTimeEquals(token, expected)) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or missing " + TOKEN_HEADER));
        }

        LocalDate day = parseIsoDateOrNull(date);
        if (day == null) day = LocalDate.now();

        Set<Integer> allowed = StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.WORKLIST_KEY);
        List<ScheduledVisitQuery.ScheduledVisit> visits;
        try {
            visits = ScheduledVisitQuery.query(dataSource, day, day, allowed, MAX_ENTRIES);
        } catch (SQLException e) {
            LOG.error("Optomed worklist query failed for {}: {}", day, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "worklist query failed"));
        }

        byte[] body = OptomedWorklistFormat.render(visits);
        LOG.info("Optomed worklist served: {} visit(s) for {}", visits.size(), day);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(TEXT_ASCII);
        // The task compares content to decide whether to re-drop the file;
        // a cached copy would defeat that and a stale list is the whole problem.
        h.setCacheControl(CacheControl.noStore());
        h.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(OptomedWorklistFormat.FILENAME).build());
        return new ResponseEntity<>(body, h, org.springframework.http.HttpStatus.OK);
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

    private String cfg(String key, String dflt) {
        String raw = config.get(key);
        return (raw == null || raw.isBlank()) ? dflt : raw.trim();
    }
}
