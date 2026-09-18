/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * P3.0 — writing a CRF value that nobody typed.
 *
 * <p>Two services do this: the retinal populator, which copies inference
 * metrics into the visit form, and the ingest populator, which ticks "this
 * modality was performed" when an image is bound. A third is coming with the
 * imaging-modality catalogue. Each wrote its own upsert, and each had to get
 * the same delicate rule right.
 *
 * <p><strong>The rule: a value a person typed is never overwritten.</strong>
 * A machine may correct its own earlier answer and nothing else. That matters
 * most in the case it looks least important — an operator who recorded that a
 * modality was <em>not</em> performed has said something a device arriving
 * later does not get to contradict silently.
 *
 * <p>Provenance is how the rule is enforced, so it is not optional: every row
 * written here carries {@code source_kind} and the id of whatever produced it,
 * in that source's own column. A row with no {@code source_kind} is a person's.
 *
 * <p>What this deliberately does not own is the audit row. The two callers
 * audit differently — one records every populate pass, the other only real
 * changes — and folding that in would have made this refactor change behaviour
 * neither caller asked to change. {@link Result} carries the previous value so
 * a caller can write its own audit row without re-reading.
 */
public final class SourcedItemDataWriter {

    private SourcedItemDataWriter() {}

    /**
     * A machine that may author CRF values, and the column recording which of
     * its outputs did.
     *
     * <p>The column name is interpolated into SQL, so it is a constant here and
     * can never come from a caller.
     */
    public enum Source {

        /** Fluid volumes and thickness from the retinal inference pipeline. */
        RETINAL_INFERENCE("retinal_inference", "source_retinal_job_id"),

        /** The "modality performed" tick that follows binding an image. */
        INGEST("ingest", "source_image_ingest_id");

        private final String kind;
        private final String column;

        Source(String kind, String column) {
            this.kind = kind;
            this.column = column;
        }

        /** The value written into {@code item_data.source_kind}. */
        public String kind() {
            return kind;
        }

        public String column() {
            return column;
        }
    }

    /** Which machine, and which of its outputs. */
    public record Ref(Source source, long id) {}

    public enum Outcome {
        /** The row was inserted or updated. */
        WRITTEN,
        /** This source had already written exactly this value. */
        UNCHANGED,
        /** A person's value is there and stands. */
        OPERATOR_VALUE_KEPT,
        /** Another machine's value is there and stands. */
        OTHER_SOURCE_KEPT
    }

    /**
     * @param itemDataId    the row that exists now, whether or not this call
     *                      touched it; null only when nothing was written and
     *                      nothing was there
     * @param previousValue what the row held before, for the caller's audit row
     */
    public record Result(Outcome outcome, Integer itemDataId, String previousValue) {

        public boolean wrote() {
            return outcome == Outcome.WRITTEN;
        }

        /** True when the value now in the row is this source's. */
        public boolean ours() {
            return outcome == Outcome.WRITTEN || outcome == Outcome.UNCHANGED;
        }
    }

    /**
     * Idempotent upsert of the {@code item_data} row for (event_crf, item).
     *
     * <p>The caller resolves the item — and should resolve it within the
     * visit's own CRF version, so a same-named item in another study's form
     * cannot be hit.
     *
     * @param actorUserId the account recorded as author: the person whose
     *                    action triggered the machine, or the system account
     *                    for something nobody triggered
     */
    public static Result upsert(Connection c, int eventCrfId, int itemId, String value,
                                Ref ref, int actorUserId) throws SQLException {
        Existing existing = findExisting(c, eventCrfId, itemId);

        if (existing == null) {
            int id = insert(c, eventCrfId, itemId, value, ref, actorUserId);
            return new Result(Outcome.WRITTEN, id, null);
        }
        if (existing.sourceKind() == null || existing.sourceKind().isBlank()) {
            return new Result(Outcome.OPERATOR_VALUE_KEPT, existing.itemDataId(), existing.value());
        }
        if (!ref.source().kind().equals(existing.sourceKind())) {
            return new Result(Outcome.OTHER_SOURCE_KEPT, existing.itemDataId(), existing.value());
        }
        if (value == null ? existing.value() == null : value.equals(existing.value())) {
            return new Result(Outcome.UNCHANGED, existing.itemDataId(), existing.value());
        }
        update(c, existing.itemDataId(), value, ref, actorUserId);
        return new Result(Outcome.WRITTEN, existing.itemDataId(), existing.value());
    }

    /* ------------------------------------------------------------------ */

    /** The live row for (event_crf, item); deleted rows do not count. */
    public record Existing(int itemDataId, String value, String sourceKind) {}

    public static Existing findExisting(Connection c, int eventCrfId, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_data_id, value, source_kind FROM item_data "
                        + " WHERE event_crf_id = ? AND item_id = ? "
                        + "   AND COALESCE(deleted, false) = false "
                        + " ORDER BY ordinal, item_data_id LIMIT 1")) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Existing(rs.getInt(1), rs.getString(2), rs.getString(3));
            }
        }
    }

    private static int insert(Connection c, int eventCrfId, int itemId, String value,
                              Ref ref, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_data "
                        + "  (item_id, event_crf_id, status_id, value, date_created, "
                        + "   owner_id, ordinal, deleted, source_kind, " + ref.source().column() + ") "
                        + "VALUES (?, ?, 1, ?, NOW(), ?, 1, false, ?, ?) "
                        + "RETURNING item_data_id")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            ps.setInt(4, actorUserId);
            ps.setString(5, ref.source().kind());
            ps.setLong(6, ref.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("INSERT item_data returned no id");
                return rs.getInt(1);
            }
        }
    }

    private static void update(Connection c, int itemDataId, String value,
                               Ref ref, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_data "
                        + "   SET value = ?, date_updated = NOW(), update_id = ?, "
                        + "       source_kind = ?, " + ref.source().column() + " = ? "
                        + " WHERE item_data_id = ?")) {
            ps.setString(1, value);
            ps.setInt(2, actorUserId);
            ps.setString(3, ref.source().kind());
            ps.setLong(4, ref.id());
            ps.setInt(5, itemDataId);
            ps.executeUpdate();
        }
    }
}
