/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

/**
 * DR-040 phase 2 — what a device request may reach, and whose it is, once it
 * carries an uploader token.
 *
 * <p>{@code UploaderTokenFilter} leaves the {@link IngestSourceService.Source}
 * of a tokened request on the request; the controllers pass it down and ask
 * here. Without a source nothing is narrowed and nothing is attributed: the
 * request is an untokened one the front door let in (exactly one active
 * location), and it does what it always did.
 *
 * <p><strong>Narrowing.</strong> With a source, a request answers only for
 * studies and sites at the source's location ({@link LocationScope#studyIdsAtLocation}),
 * intersected with whatever scope the deployment configured for the surface
 * ({@link StudyScopeConfig}). A subject, visit or CRF outside it is treated as
 * unknown by every caller: the same status, the same body, so a token tells its
 * holder nothing about another place.
 *
 * <p><strong>Failing closed.</strong> A location that cannot be read narrows to
 * no study at all, never to every study.
 */
final class DeviceScope {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceScope.class);

    private DeviceScope() {}

    /**
     * The studies a request may reach on a surface: its configured scope
     * ({@code configKey}) narrowed to the source's location.
     *
     * @return null when nothing narrows it (no source, and the key is unset);
     *         otherwise the allowed study ids, possibly empty
     */
    static Set<Integer> studies(DataSource dataSource, String configKey, IngestSourceService.Source source) {
        return narrow(StudyScopeConfig.studyIdsFor(dataSource, configKey), atLocation(dataSource, source));
    }

    /**
     * The studies and sites at the source's location; null for a request with
     * no source (no narrowing), empty when the location cannot be read.
     */
    static Set<Integer> atLocation(DataSource dataSource, IngestSourceService.Source source) {
        if (source == null) return null;
        Integer location = source.locationId();
        if (location == null) return Set.of();
        try (Connection c = dataSource.getConnection()) {
            return LocationScope.studyIdsAtLocation(c, location);
        } catch (SQLException | RuntimeException e) {
            LOG.error("could not read the studies at location {} — restricting to no studies: {}",
                    location, e.getMessage());
            return Set.of();
        }
    }

    /** The intersection; null means "every study" on either side. */
    static Set<Integer> narrow(Set<Integer> configured, Set<Integer> atLocation) {
        if (atLocation == null) return configured;
        if (configured == null) return atLocation;
        Set<Integer> both = new LinkedHashSet<>(configured);
        both.retainAll(atLocation);
        return both;
    }

    /** True when the study is allowed: no narrowing at all, or it is in the set. */
    static boolean allows(Set<Integer> studies, int studyId) {
        return studies == null || studies.contains(studyId);
    }

    /**
     * Whether the subject is enrolled at a study the set allows. A subject that
     * does not exist is not allowed either, which is what lets a caller answer
     * "unknown" for both.
     */
    static boolean subjectAllowed(Connection c, Set<Integer> studies, int studySubjectId) throws SQLException {
        if (studies == null) return true;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && studies.contains(rs.getInt(1));
            }
        }
    }

    /** As {@link #subjectAllowed} for the subject of a visit. */
    static boolean visitAllowed(Connection c, Set<Integer> studies, int studyEventId) throws SQLException {
        if (studies == null) return true;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ss.study_id FROM study_event se JOIN study_subject ss "
                        + "ON ss.study_subject_id = se.study_subject_id WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && studies.contains(rs.getInt(1));
            }
        }
    }

    /** As {@link #subjectAllowed} for the subject of a CRF instance. */
    static boolean eventCrfAllowed(Connection c, Set<Integer> studies, int eventCrfId) throws SQLException {
        if (studies == null) return true;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ss.study_id FROM event_crf ec JOIN study_subject ss "
                        + "ON ss.study_subject_id = ec.study_subject_id WHERE ec.event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && studies.contains(rs.getInt(1));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* What it may be told about files                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Whether the request may be told that an ingest item exists: always for a
     * request with no source; with one, only for an item that arrived at its
     * location. A duplicate at another place is "already uploaded" and no more.
     */
    static boolean mayName(DataSource dataSource, IngestSourceService.Source source, long ingestItemId) {
        if (source == null) return true;
        if (source.locationId() == null) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM ingest_item WHERE ingest_item_id = ? AND origin_location_id = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, source.locationId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("could not check the location of ingest item {}: {}", ingestItemId, e.getMessage());
            return false;
        }
    }

    /** {@link #mayName} as the predicate {@code IngestUploadService.Upload} takes; null (unrestricted) without a source. */
    static LongPredicate itemsOf(DataSource dataSource, IngestSourceService.Source source) {
        return source == null ? null : id -> mayName(dataSource, source, id);
    }

    /**
     * A 409 "already uploaded" answer, stripped of the ids of items the request
     * may not be told about. Anything else, and every answer to a request with no
     * source, passes unchanged.
     */
    static ResponseEntity<?> maskDuplicate(DataSource dataSource, IngestSourceService.Source source,
                                           ResponseEntity<?> answer) {
        if (source == null || answer.getStatusCode().value() != 409
                || !(answer.getBody() instanceof Map<?, ?> body)
                || !(body.containsKey("existingIngestItemId") || body.containsKey("existingJobId"))) {
            return answer;
        }
        if (body.get("existingIngestItemId") instanceof Number n && mayName(dataSource, source, n.longValue())) {
            return answer;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("message", body.get("message"));
        masked.put("duplicate", true);
        return ResponseEntity.status(409).body(masked);
    }

    /* ------------------------------------------------------------------ */
    /* Whose file it is                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Where a file delivered by a device request comes from: the source's
     * location and the source, or, with no source, the only active location
     * (null while several exist) and no source. A staff upload does not use this;
     * it keeps {@link LocationScope#originForStaffUpload}.
     */
    record Origin(Integer locationId, Long ingestSourceId) {

        static Origin of(Connection c, IngestSourceService.Source source) throws SQLException {
            return source != null
                    ? new Origin(source.locationId(), source.id())
                    : new Origin(LocationScope.defaultLocationId(c), null);
        }
    }
}
