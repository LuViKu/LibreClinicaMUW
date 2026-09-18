/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * P3.1 — the one place a row enters {@code ingest_item}.
 *
 * <p>The DICOM endpoint and the upload portal each built their own INSERT:
 * twenty-one and sixteen positional parameters, listing overlapping subsets of
 * the same columns in different orders. Adding a column meant finding both and
 * renumbering by hand, and a misnumbered {@code setString} does not fail — it
 * writes the accession number into the laterality column and the row looks
 * plausible until somebody reads it.
 *
 * <p>So the columns are named once, here, and callers name what they are
 * setting:
 *
 * <pre>
 * long id = IngestItemRepository.newItem(Kind.DICOM, "dicom", storedPath)
 *         .device(aeTitle)
 *         .sopInstanceUid(uid)
 *         .boundTo(subjectId, eventId, eventCrfId, "worklist")
 *         .insert(connection);
 * </pre>
 *
 * <p>Only the columns a caller sets are written; the rest take their database
 * defaults. That is what keeps the statement honest as the table grows —
 * P3.2's OCT path sets {@code sha256} and {@code scanIndex}, which no current
 * caller does, without touching either of them.
 *
 * <p>{@code kind} is taken from {@link IngestArtifactStore.Kind} rather than a
 * parallel enum, so the directory a file is stored under and the kind recorded
 * against it cannot disagree.
 */
public final class IngestItemRepository {

    private IngestItemRepository() {}

    /**
     * @param kind       what the file is; {@code ingest_item.kind} is NOT NULL
     *                   because the queue routes on it
     * @param sourceKind how it arrived — 'dicom', 'upload', 'portal-oct', 'api'
     * @param storedPath where it landed, which is the one thing every row has
     */
    public static Builder newItem(IngestArtifactStore.Kind kind, String sourceKind, String storedPath) {
        return new Builder(kind, sourceKind, storedPath);
    }

    /** Accumulates named columns, then writes them in one statement. */
    public static final class Builder {

        private final List<String> columns = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();
        private final List<Integer> nullTypes = new ArrayList<>();

        private Builder(IngestArtifactStore.Kind kind, String sourceKind, String storedPath) {
            if (kind == null) throw new IllegalArgumentException("kind is required");
            if (sourceKind == null || sourceKind.isBlank()) {
                throw new IllegalArgumentException("sourceKind is required");
            }
            if (storedPath == null || storedPath.isBlank()) {
                throw new IllegalArgumentException("storedPath is required");
            }
            set("kind", kind.dir(), Types.VARCHAR);
            set("source_kind", sourceKind, Types.VARCHAR);
            set("stored_path", storedPath, Types.VARCHAR);
            set("received_at", Timestamp.from(Instant.now()), Types.TIMESTAMP);
            set("status", "UNBOUND", Types.VARCHAR);
        }

        private Builder set(String column, Object value, int sqlType) {
            int existing = columns.indexOf(column);
            if (existing >= 0) {
                values.set(existing, value);
                return this;
            }
            columns.add(column);
            values.add(value);
            nullTypes.add(sqlType);
            return this;
        }

        /* ---------------- the file ---------------- */

        public Builder previewPngPath(String v) { return set("preview_png_path", v, Types.VARCHAR); }
        public Builder originalFilename(String v) { return set("original_filename", v, Types.VARCHAR); }
        public Builder contentType(String v) { return set("content_type", v, Types.VARCHAR); }

        /**
         * The digest and size {@link IngestArtifactStore} computed on the way
         * to disk. Recording them is what makes a re-sent file detectable —
         * the partial unique index on (sha256, scan_index) refuses the second
         * row rather than queueing one acquisition twice.
         */
        public Builder digest(String sha256, long byteSize) {
            set("sha256", sha256, Types.VARCHAR);
            return set("byte_size", byteSize, Types.BIGINT);
        }

        /** Which scan within a multi-scan file; absent for single artifacts. */
        public Builder scanIndex(Integer v) { return set("scan_index", v, Types.INTEGER); }

        /* ---------------- the device ---------------- */

        /**
         * Which camera, whichever ingress it came through — the DICOM calling
         * AE title, or the portal's device field. {@code source_ae_title} keeps
         * the raw DICOM value alongside.
         */
        public Builder device(String v) { return set("device", v, Types.VARCHAR); }
        public Builder sourceAeTitle(String v) { return set("source_ae_title", v, Types.VARCHAR); }
        public Builder modality(String v) { return set("modality", v, Types.VARCHAR); }

        /* ---------------- DICOM identity ---------------- */

        public Builder sopInstanceUid(String v) { return set("sop_instance_uid", v, Types.VARCHAR); }
        public Builder sopClassUid(String v) { return set("sop_class_uid", v, Types.VARCHAR); }
        public Builder studyInstanceUid(String v) { return set("study_instance_uid", v, Types.VARCHAR); }
        public Builder seriesInstanceUid(String v) { return set("series_instance_uid", v, Types.VARCHAR); }

        /* ---------------- what the file says about the patient ---------------- */

        public Builder patientId(String v) { return set("patient_id", v, Types.VARCHAR); }
        public Builder patientName(String v) { return set("patient_name", v, Types.VARCHAR); }
        public Builder accessionNumber(String v) { return set("accession_number", v, Types.VARCHAR); }
        public Builder acquisitionDate(LocalDate v) { return set("acquisition_date", v, Types.DATE); }
        public Builder laterality(String v) { return set("laterality", v, Types.VARCHAR); }

        /**
         * Who the platform thinks this belongs to, before anyone confirms it.
         *
         * <p>Distinct from the binding: a suggestion the resolver made is not a
         * decision somebody took, and the inbox has to be able to show the
         * first without implying the second.
         */
        public Builder candidateStudySubjectId(Integer v) {
            return set("candidate_study_subject_id", v, Types.INTEGER);
        }

        /* ---------------- the binding ---------------- */

        /**
         * Land the row already bound to a visit.
         *
         * <p>For the paths where the file identifies its own visit: a DICOM
         * study answering one of our worklist items, or an upload where the
         * operator picked the visit on the form. Everything else lands UNBOUND
         * for somebody to reconcile.
         *
         * @param matchPolicy how the binding was arrived at — 'worklist',
         *                    'portal', 'manual', 'suggested', 'visit-picked',
         *                    'backfill'
         * @param boundByUserId who decided; null when nobody did, which is the
         *                    case for both automatic paths
         */
        public Builder boundTo(int studySubjectId, int studyEventId, Integer eventCrfId,
                               String matchPolicy, Integer boundByUserId) {
            set("status", "BOUND", Types.VARCHAR);
            set("match_policy", matchPolicy, Types.VARCHAR);
            set("bound_study_subject_id", studySubjectId, Types.INTEGER);
            set("bound_study_event_id", studyEventId, Types.INTEGER);
            set("bound_event_crf_id", eventCrfId, Types.INTEGER);
            set("bound_by_user_id", boundByUserId, Types.INTEGER);
            return set("bound_at", Timestamp.from(Instant.now()), Types.TIMESTAMP);
        }

        /** As {@link #boundTo}, for the automatic paths that have no user. */
        public Builder boundTo(int studySubjectId, int studyEventId, Integer eventCrfId,
                               String matchPolicy) {
            return boundTo(studySubjectId, studyEventId, eventCrfId, matchPolicy, null);
        }

        /** @return the new {@code ingest_item_id} */
        public long insert(Connection c) throws SQLException {
            String sql = "INSERT INTO ingest_item (" + String.join(", ", columns) + ") VALUES ("
                    + "?, ".repeat(columns.size() - 1) + "?)";
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < values.size(); i++) {
                    Object v = values.get(i);
                    if (v == null) {
                        ps.setNull(i + 1, nullTypes.get(i));
                    } else if (v instanceof LocalDate d) {
                        ps.setObject(i + 1, d);
                    } else {
                        ps.setObject(i + 1, v);
                    }
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                    throw new SQLException("ingest_item INSERT returned no PK");
                }
            }
        }
    }
}
