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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioDashboardClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;

/**
 * DR-031 — the Remidio side of the worklist: make sure every subject with a
 * scheduled visit exists as a patient in Remidio's cloud, so the photographer
 * picks the label from the app's list instead of typing it.
 *
 * <p>Reads the same scheduled visits the DICOM and Optomed worklists serve
 * ({@link ScheduledVisitQuery}, restricted to {@code core.dicom.worklist.studyOids}),
 * so the three never disagree about who is on the schedule. For each label it
 * has not resolved before it asks Remidio whether the MRN exists, creates the
 * patient only when it does not, and remembers the answer — a patient in
 * Remidio cannot be deleted, so the sync is deliberately conservative: only
 * labels with a live visit in the window, one lookup before every create,
 * and nothing speculative.
 *
 * <p>The patient carries a placeholder identity, as on the Optomed worklist:
 * MRN and first name are the subject label, last name is the study name, the
 * date of birth is the epoch, the sex is the subject's mapped onto the two
 * values the dashboard knows. Nothing real beyond the label.
 *
 * <p>It also creates one exam per visit ({@code createExams}, on by
 * default): the FOP app has no patient list, only an exam list, so a patient
 * without an exam is invisible on the phone. The exam is named for the
 * photographer ({@code HAE-002 Baseline}); the visit it stands for is kept
 * in {@code remidio_visit_exam}, and a capture made into that exam binds
 * straight to its visit in the pull, without going through label + date.
 */
final class RemidioPatientSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(RemidioPatientSyncService.class);

    static final int MAX_VISITS = 500;
    static final String PLACEHOLDER_LAST_NAME_FALLBACK = "STUDY";
    static final String EXAM_LOCAL_ID_PREFIX = "LIBRECLINICA::";

    record Summary(LocalDate from, LocalDate to, int visits, int labels, int patientsFound,
                   int patientsCreated, int examsCreated, int failed) {

        String line() {
            return from + ".." + to + ": visits=" + visits + " labels=" + labels + " found=" + patientsFound
                    + " created=" + patientsCreated + " exams=" + examsCreated + " failed=" + failed;
        }
    }

    private final DataSource dataSource;
    private final RemidioDashboardClient client;

    RemidioPatientSyncService(DataSource dataSource, RemidioDashboardClient client) {
        this.dataSource = dataSource;
        this.client = client;
    }

    /**
     * One pass over the visits scheduled in {@code [from, to]}.
     *
     * @throws RemidioException when the dashboard cannot be reached or refuses
     *                          the account — nothing per item, the whole pass
     *                          is off; per-item remote errors are counted as
     *                          failed and the pass goes on
     */
    Summary sync(LocalDate from, LocalDate to, boolean createExams) throws RemidioException {
        Set<Integer> allowed = StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.WORKLIST_KEY);
        List<ScheduledVisitQuery.ScheduledVisit> visits;
        try {
            visits = ScheduledVisitQuery.query(dataSource, from, to, allowed, MAX_VISITS);
        } catch (SQLException e) {
            LOG.error("remidio sync: scheduled-visit query failed: {}", e.getMessage());
            return new Summary(from, to, 0, 0, 0, 0, 0, 1);
        }

        Map<String, Long> known = knownPatients();
        int found = 0, created = 0, exams = 0, failed = 0, labels = 0;
        // One line per distinct reason, not per subject. A misconfiguration
        // fails identically on every label, and logging it N times per pass
        // says nothing the first one did not — while burying anything else
        // that went wrong in the same pass.
        Map<String, Integer> failureReasons = new java.util.LinkedHashMap<>();
        for (ScheduledVisitQuery.ScheduledVisit v : visits) {
            String label = v.subjectLabel() == null ? "" : v.subjectLabel().trim();
            if (label.isEmpty()) continue;

            Long patientId = known.get(label);
            if (patientId == null) {
                labels++;
                try {
                    Optional<Long> existing = client.findPatientId(label);
                    if (existing.isPresent()) {
                        patientId = existing.get();
                        remember(label, patientId, false);
                        found++;
                    } else {
                        patientId = client.createPatient(label, label, lastNameFor(v.studyName()), 0L,
                                sexOf(v.gender()));
                        remember(label, patientId, true);
                        created++;
                    }
                    known.put(label, patientId);
                } catch (RemidioException e) {
                    if (isFatal(e)) throw e;
                    failed++;
                    note(failureReasons, "resolving a subject's patient", e);
                    continue;
                }
            }

            if (createExams && !examExists(v.studyEventId())) {
                String name = examNameFor(label, v.eventLabel());
                try {
                    long examId = client.createExam(patientId, name, examDateMs(v.date(), v.time()),
                            EXAM_LOCAL_ID_PREFIX + v.studyEventId());
                    rememberExam(v.studyEventId(), examId, name);
                    exams++;
                } catch (RemidioException e) {
                    if (isFatal(e)) throw e;
                    failed++;
                    note(failureReasons, "creating a visit's exam", e);
                }
            }
        }
        failureReasons.forEach((reason, count) ->
                LOG.warn("remidio sync: {} item(s) failed — {}", count, reason));
        return new Summary(from, to, visits.size(), labels, found, created, exams, failed);
    }

    /** Counts one failure under its reason, so the pass can report each once. */
    private static void note(Map<String, Integer> reasons, String what, RemidioException e) {
        String key = what + " (" + e.reason() + "): " + e.getMessage();
        reasons.merge(key, 1, Integer::sum);
    }

    /* ------------------------------------------------------------------ */
    /* mapping                                                             */
    /* ------------------------------------------------------------------ */

    /** The dashboard knows MALE and FEMALE; anything else becomes the placeholder MALE. */
    static String sexOf(String gender) {
        String g = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);
        if (g.startsWith("f")) return "FEMALE";
        return "MALE";
    }

    /** The study name as the placeholder last name, trimmed to something the app can show. */
    static String lastNameFor(String studyName) {
        String s = studyName == null ? "" : studyName.trim();
        if (s.isEmpty()) return PLACEHOLDER_LAST_NAME_FALLBACK;
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /**
     * What the app shows in its exam list — the one place the photographer
     * reads. Label and visit, as on the Optomed worklist; the visit binding
     * itself goes through {@code remidio_visit_exam}, never through this text.
     */
    static String examNameFor(String label, String eventLabel) {
        String ev = eventLabel == null ? "" : eventLabel.trim();
        String s = ev.isEmpty() ? label : label + " " + ev;
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** The visit's scheduled instant in the clinic's zone, as the dashboard's epoch milliseconds. */
    static long examDateMs(String isoDate, LocalTime time) {
        LocalDate day = LocalDate.parse(isoDate);
        return day.atTime(time == null ? LocalTime.NOON : time)
                .atZone(RemidioPullService.CLINIC_ZONE).toInstant().toEpochMilli();
    }

    /** Unreachable or refused: the rest of the pass would fail the same way. */
    private static boolean isFatal(RemidioException e) {
        return e.reason() == RemidioException.Reason.UNREACHABLE
                || e.reason() == RemidioException.Reason.UNAUTHORIZED
                || e.reason() == RemidioException.Reason.UNCONFIGURED;
    }

    /* ------------------------------------------------------------------ */
    /* memory                                                              */
    /* ------------------------------------------------------------------ */

    private Map<String, Long> knownPatients() {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT subject_label, remidio_patient_id FROM remidio_patient WHERE site_id = ?")) {
            ps.setLong(1, client.settings().siteId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            // An empty map means one lookup per label — never a duplicate
            // create, because the lookup precedes every create.
            LOG.warn("remidio sync: could not read known patients: {}", e.getMessage());
        }
        return out;
    }

    private void remember(String label, long patientId, boolean createdByUs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO remidio_patient (subject_label, site_id, remidio_patient_id, created_by_us) "
                             + "VALUES (?, ?, ?, ?) ON CONFLICT (subject_label) DO UPDATE "
                             + "SET remidio_patient_id = EXCLUDED.remidio_patient_id")) {
            ps.setString(1, label);
            ps.setLong(2, client.settings().siteId());
            ps.setLong(3, patientId);
            ps.setBoolean(4, createdByUs);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("remidio sync: could not remember the patient for a subject label: {}", e.getMessage());
        }
    }

    private boolean examExists(int studyEventId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM remidio_visit_exam WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // "Exists" on doubt: a missing exam costs the photographer a tap,
            // a duplicate exam is a permanent record in the vendor's cloud.
            LOG.warn("remidio sync: could not check the exam of visit {}: {}", studyEventId, e.getMessage());
            return true;
        }
    }

    private void rememberExam(int studyEventId, long examId, String accession) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO remidio_visit_exam (study_event_id, remidio_exam_id, exam_custom_id) "
                             + "VALUES (?, ?, ?) ON CONFLICT (study_event_id) DO NOTHING")) {
            ps.setInt(1, studyEventId);
            ps.setLong(2, examId);
            ps.setString(3, accession);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("remidio sync: could not remember the exam of visit {}: {}", studyEventId, e.getMessage());
        }
    }
}
