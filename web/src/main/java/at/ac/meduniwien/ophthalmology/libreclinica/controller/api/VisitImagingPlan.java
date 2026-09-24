/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 * For details see: https://libreclinica.org/license
 * LibreClinica, copyright (C) 2020-2026
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * DR-034 — what a visit definition expects from the imaging catalogue, and
 * what that means for one particular visit.
 *
 * <p>Three questions are answered here, all from {@code event_definition_imaging}:
 * <ul>
 *   <li><b>Which inference tasks run on this file?</b> — {@link #tasksFor}.
 *       A file's modality looked up in its visit definition's plan; a visit
 *       definition without a plan falls back to the per-visit task list that
 *       predates the plan ({@code event_definition_retinal_task}) and then to
 *       the {@code fluid} default, so a study without an imaging catalogue
 *       behaves as it always has.</li>
 *   <li><b>Is this visit's imaging complete?</b> — {@link #coverage}. Every
 *       entry set against what is actually filed against the visit: a
 *       required entry with nothing behind it is what the sign preflight
 *       reports.</li>
 *   <li><b>What does the visit page show?</b> — the same coverage, one row per
 *       entry, present or missing.</li>
 * </ul>
 *
 * <p>Static and connection-passing on purpose: the three callers (upload
 * commit, sign preflight, visit page) each already hold a connection and a
 * transaction of their own, and none of this needs state.
 */
public final class VisitImagingPlan {

    /** What runs when a visit definition has neither a plan nor a legacy task list. */
    public static final String DEFAULT_TASK = "fluid";

    public static final String REQUIRED = "required";
    public static final String OPTIONAL = "optional";

    private VisitImagingPlan() {}

    /** One plan row, joined with the catalogue entry it names. */
    public record Entry(long id, int modalityId, String code, String labelDe, String labelEn,
                        String device, String kindsAccepted, String requirement,
                        String laterality, List<String> tasks) {

        public boolean required() {
            return REQUIRED.equals(requirement);
        }

        /** True when the catalogue entry accepts OCT volumes — the only kind inference runs on. */
        public boolean acceptsE2e() {
            return acceptsKind(kindsAccepted, "e2e");
        }
    }

    /** What is known about one file filed against the visit, for matching. */
    public record PresentFile(Integer modalityId, String device, String laterality) {}

    /** One plan entry against the visit's files. */
    public record Coverage(Entry entry, int presentOD, int presentOS, int presentTotal, boolean satisfied) {}

    /** Where a file's task list came from — logged, so a silent "no job" can be explained. */
    public enum TaskSource { PLAN, PLAN_NO_MATCH, LEGACY, DEFAULT, UNBOUND }

    public record TaskResolution(List<String> tasks, TaskSource source) {}

    /* ------------------------------------------------------------------ */
    /* Pure logic                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Each entry against the files present, in plan order.
     *
     * <p>A file matches an entry by catalogue id; a file that was never
     * classified (older rows, the OCT portal before this change) matches by
     * device key instead. The laterality rule: an entry naming one eye needs a
     * file of that eye (a file marked OU covers both), {@code OU} needs both
     * eyes, and no laterality is satisfied by any file, whatever its eye.
     */
    public static List<Coverage> coverage(List<Entry> entries, List<PresentFile> files) {
        List<Coverage> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            int od = 0;
            int os = 0;
            int total = 0;
            for (PresentFile f : files) {
                if (!matches(e, f)) continue;
                total++;
                String lat = f.laterality() == null ? "" : f.laterality().trim().toUpperCase(Locale.ROOT);
                if ("OD".equals(lat) || "OU".equals(lat)) od++;
                if ("OS".equals(lat) || "OU".equals(lat)) os++;
            }
            boolean ok;
            String want = e.laterality() == null ? "" : e.laterality().trim().toUpperCase(Locale.ROOT);
            switch (want) {
                case "OD" -> ok = od > 0;
                case "OS" -> ok = os > 0;
                case "OU" -> ok = od > 0 && os > 0;
                default -> ok = total > 0;
            }
            out.add(new Coverage(e, od, os, total, ok));
        }
        return out;
    }

    static boolean matches(Entry e, PresentFile f) {
        if (f.modalityId() != null) return f.modalityId() == e.modalityId();
        if (e.device() == null || e.device().isBlank() || f.device() == null) return false;
        return e.device().trim().equalsIgnoreCase(f.device().trim());
    }

    /** {@code kinds_accepted} is a comma list; a blank list accepts no file. */
    public static boolean acceptsKind(String kindsAccepted, String kind) {
        if (kindsAccepted == null || kind == null) return false;
        for (String k : kindsAccepted.split(",")) {
            if (k.trim().equalsIgnoreCase(kind.trim())) return true;
        }
        return false;
    }

    public static List<String> splitTasks(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : csv.split(",")) {
            String n = t.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    public static String joinTasks(List<String> tasks) {
        return tasks == null ? "" : String.join(",", tasks);
    }

    /** A one-line rendering of a plan, for the audit row's old/new value. */
    public static String describe(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.code()).append(':').append(e.requirement())
              .append(':').append(e.laterality() == null ? "any" : e.laterality())
              .append(':').append(joinTasks(e.tasks()));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Loading                                                             */
    /* ------------------------------------------------------------------ */

    private static final String ENTRY_SELECT =
            "SELECT p.id, p.imaging_modality_id, m.code, m.label_de, m.label_en, m.device, "
                    + "       m.kinds_accepted, p.requirement, p.laterality, p.retinal_tasks "
                    + "  FROM event_definition_imaging p "
                    + "  JOIN imaging_modality m ON m.imaging_modality_id = p.imaging_modality_id ";

    /** The plan of one visit definition, in catalogue order. */
    public static List<Entry> forDefinition(Connection c, int sedId) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(ENTRY_SELECT
                + " WHERE p.study_event_definition_id = ? ORDER BY m.ordinal, m.code")) {
            ps.setInt(1, sedId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        }
        return out;
    }

    /** The plan of the definition behind one scheduled visit; empty when the visit is unknown. */
    public static List<Entry> forStudyEvent(Connection c, int studyEventId) throws SQLException {
        Integer sed = definitionOfStudyEvent(c, studyEventId);
        return sed == null ? List.of() : forDefinition(c, sed);
    }

    public static Integer definitionOfStudyEvent(Connection c, int studyEventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_event_definition_id FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        }
    }

    public static Integer definitionOfEventCrf(Connection c, int eventCrfId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT se.study_event_definition_id FROM event_crf ec "
                        + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                        + " WHERE ec.event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        }
    }

    /**
     * The top-level study a visit binding belongs to (a site's visit resolves
     * to its parent), or null when neither id is given or known.
     */
    public static Integer studyOfBinding(Connection c, Integer eventCrfId, Integer studyEventId)
            throws SQLException {
        String sql;
        int id;
        if (eventCrfId != null) {
            sql = "SELECT s.study_id, s.parent_study_id FROM event_crf ec "
                    + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                    + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                    + "  JOIN study s ON s.study_id = ss.study_id "
                    + " WHERE ec.event_crf_id = ?";
            id = eventCrfId;
        } else if (studyEventId != null) {
            sql = "SELECT s.study_id, s.parent_study_id FROM study_event se "
                    + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                    + "  JOIN study s ON s.study_id = ss.study_id "
                    + " WHERE se.study_event_id = ?";
            id = studyEventId;
        } else {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int parent = rs.getInt(2);
                return parent > 0 ? parent : rs.getInt(1);
            }
        }
    }

    /**
     * The one active catalogue entry of a study that accepts OCT volumes, or
     * null when there is none or more than one — a guess between two would be
     * filed as a fact.
     */
    public static Integer e2eModalityOf(Connection c, int studyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT imaging_modality_id FROM imaging_modality "
                        + " WHERE study_id = ? AND COALESCE(status_id, 1) = 1 "
                        + "   AND (',' || lower(COALESCE(kinds_accepted, '')) || ',') LIKE '%,e2e,%' "
                        + " ORDER BY ordinal, code")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int id = rs.getInt(1);
                return rs.next() ? null : Integer.valueOf(id);
            }
        }
    }

    /** The files filed against one visit, as much of each as matching needs. */
    public static List<PresentFile> filesOf(Connection c, int studyEventId) throws SQLException {
        List<PresentFile> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT imaging_modality_id, device, laterality FROM ingest_item "
                        + " WHERE bound_study_event_id = ? AND status = 'BOUND'")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int mid = rs.getInt(1);
                    out.add(new PresentFile(rs.wasNull() ? null : mid, rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    /**
     * The tasks to enqueue for a file of {@code modalityId} filed against the
     * visit named by one of the two ids.
     *
     * <p>No binding: nothing to run. A visit definition with a plan: the entry
     * for the file's modality, and nothing when the modality has no entry or
     * is unknown — a plan that names what runs on what is a plan that says
     * nothing runs on the rest. No plan: the legacy per-visit list, then the
     * default.
     */
    public static TaskResolution tasksFor(Connection c, Integer eventCrfId, Integer studyEventId,
                                          Integer modalityId) throws SQLException {
        Integer sedId = null;
        if (eventCrfId != null) sedId = definitionOfEventCrf(c, eventCrfId);
        if (sedId == null && studyEventId != null) sedId = definitionOfStudyEvent(c, studyEventId);
        if (sedId == null) return new TaskResolution(List.of(), TaskSource.UNBOUND);

        List<Entry> plan = forDefinition(c, sedId);
        if (!plan.isEmpty()) {
            if (modalityId != null) {
                for (Entry e : plan) {
                    if (e.modalityId() == modalityId) return new TaskResolution(e.tasks(), TaskSource.PLAN);
                }
            }
            return new TaskResolution(List.of(), TaskSource.PLAN_NO_MATCH);
        }

        List<String> legacy = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT task FROM event_definition_retinal_task "
                        + " WHERE study_event_definition_id = ? ORDER BY id")) {
            ps.setInt(1, sedId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) legacy.add(rs.getString(1));
            }
        }
        if (!legacy.isEmpty()) return new TaskResolution(legacy, TaskSource.LEGACY);
        return new TaskResolution(List.of(DEFAULT_TASK), TaskSource.DEFAULT);
    }

    private static Entry read(ResultSet rs) throws SQLException {
        return new Entry(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), splitTasks(rs.getString(10)));
    }

    /** Test seam: an entry with only what {@link #coverage} reads. */
    static Entry entry(int modalityId, String code, String device, String requirement,
                       String laterality, String... tasks) {
        return new Entry(0, modalityId, code, code, code, device, "", requirement, laterality,
                Arrays.asList(tasks));
    }
}
