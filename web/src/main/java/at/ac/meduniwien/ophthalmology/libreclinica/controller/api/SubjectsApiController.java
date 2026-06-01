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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 — Subject Matrix adapter.
 *
 * <p>Exposes the Investigator's Subject Matrix at
 * {@code GET /pages/api/v1/subjects} as JSON for the Vue SPA. The
 * legacy JSP path at {@code /ListStudySubjects} keeps working in
 * parallel during the bake-in window per DR-018.
 *
 * <p><strong>Milestone 2 (subject-matrix completion).</strong> The
 * slice #1 adapter shipped identity columns only ({@code id},
 * {@code secondaryId}, {@code siteOid}, {@code siteLabel},
 * {@code gender}, {@code yearOfBirth}, {@code enrolledOn}). M2
 * populates the three stubbed fields from real DB data:
 * <ul>
 *   <li>{@code events[]} — walks
 *       {@link StudyEventDAO#findAllByStudySubject(StudySubjectBean)} per
 *       subject, joining each event to its {@link StudyEventDefinitionBean}
 *       for the OID + display label. Ordered by definition ordinal so
 *       V1 / V2 / V3 render in protocol order.</li>
 *   <li>{@code signed} — true iff the {@code study_subject.status_id}
 *       is {@code 8 (SIGNED)}. The legacy
 *       {@code SignStudySubjectServlet} updates both the
 *       {@code study_subject} row and every {@code study_event} row in
 *       one transaction, so the {@code study_subject} status is the
 *       authoritative indicator and isn't subject to drift from later
 *       event-status flips. (Brief's converse guard: an unsigned
 *       {@code study_subject} never reports signed regardless of the
 *       per-event statuses.)</li>
 *   <li>{@code openQueries} — count of {@code discrepancy_note} rows
 *       attached (via {@code dn_item_data_map}) to an {@code item_data}
 *       belonging to an {@code event_crf} of the event, where the note
 *       is a parent (parent_dn_id IS NULL) and the resolution status is
 *       open ({@code resolution_status_id IN (1,2,3)} — New / Updated /
 *       Resolution Proposed). Closed (4) and Not Applicable (5) do not
 *       count. Computed in a single batch query per study (one round
 *       trip total, not N×M).</li>
 * </ul>
 *
 * <p><strong>Active study selection:</strong> reads from
 * {@code session.getAttribute("study")} which is set by the legacy
 * {@code /MainMenu} flow or by the M1
 * {@code POST /pages/api/v1/me/activeStudy} endpoint. If no study is
 * bound the endpoint returns {@code 400 Bad Request}.
 *
 * <p><strong>Authorization:</strong> The chain-level
 * {@code .anyRequest().hasRole("USER")} rule in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig}
 * gates this controller. Per-site filtering for non-investigator
 * roles is handled implicitly via the legacy
 * {@link StudySubjectDAO#findAllByStudyId(int)} call's study scoping;
 * cross-site authorization is a separate compliance slice.
 */
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectsApiController.class);

    private final DataSource dataSource;

    @Autowired
    public SubjectsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            // SecurityConfig should have blocked this already; defence-in-depth.
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);

        List<StudySubjectBean> rows = studySubjectDAO.findAllByStudyId(currentStudy.getId());
        if (rows == null) rows = Collections.emptyList();

        // Cache SubjectBean lookups so a study with multiple participations
        // for the same person doesn't re-hit subject table N times.
        Map<Integer, SubjectBean> subjectCache = new HashMap<>();
        // Cache StudyEventDefinitionBean lookups across subjects — the
        // matrix typically has very few definitions per study (3 in the
        // demo seed) and N×M lookups would be wasteful.
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();

        // Single-query open-query aggregation per study_event_id so the
        // per-event openQueries cell is filled in O(1) per cell instead
        // of one DAO round trip per event.
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());

        List<SubjectListItemDto> out = new ArrayList<>(rows.size());
        for (StudySubjectBean ss : rows) {
            SubjectBean subj = subjectCache.computeIfAbsent(ss.getSubjectId(), subjectDAO::findByPK);
            out.add(toDto(ss, subj, currentStudy, studyEventDAO, studyEventDefinitionDAO,
                    definitionCache, openQueriesByEvent));
        }

        LOG.debug("Subject Matrix adapter served {} rows for study {} (user {})",
                out.size(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.ok(out);
    }

    private SubjectListItemDto toDto(StudySubjectBean ss, SubjectBean subj, StudyBean study,
                                     StudyEventDAO studyEventDAO,
                                     StudyEventDefinitionDAO studyEventDefinitionDAO,
                                     Map<Integer, StudyEventDefinitionBean> definitionCache,
                                     Map<Integer, Integer> openQueriesByEvent) {
        String secondaryId = (ss.getSecondaryLabel() == null || ss.getSecondaryLabel().isBlank())
                ? null : ss.getSecondaryLabel();
        String gender = mapGender(subj == null ? '\0' : subj.getGender());
        Integer yearOfBirth = extractYear(subj);
        String enrolledOn = formatIsoDate(ss.getEnrollmentDate());

        // Build per-event status cells. Order by definition ordinal so V1
        // appears before V2 etc. — the SPA renders them column-by-column.
        List<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        if (events == null) events = Collections.emptyList();

        List<SubjectListItemDto.EventCellDto> eventCells = new ArrayList<>(events.size());
        int subjectOpenQueries = 0;
        for (StudyEventBean ev : events) {
            StudyEventDefinitionBean def = definitionCache.computeIfAbsent(
                    ev.getStudyEventDefinitionId(), studyEventDefinitionDAO::findByPK);
            int statusId = ev.getSubjectEventStatus() == null
                    ? 0
                    : ev.getSubjectEventStatus().getId();
            String status = mapSubjectEventStatus(statusId);
            int eventOpenQueries = openQueriesByEvent.getOrDefault(ev.getId(), 0);
            subjectOpenQueries += eventOpenQueries;

            eventCells.add(new SubjectListItemDto.EventCellDto(
                    def == null ? null : def.getOid(),
                    def == null ? null : def.getName(),
                    status,
                    eventOpenQueries
            ));
        }
        // Order by the definition's ordinal so the matrix columns line up
        // with the protocol's visit order. Null-ordinal events (shouldn't
        // happen in practice) sort last.
        eventCells.sort(Comparator.comparingInt((SubjectListItemDto.EventCellDto cell) -> {
            if (cell.eventDefinitionOid() == null) return Integer.MAX_VALUE;
            StudyEventDefinitionBean d = findDefinitionByOid(definitionCache, cell.eventDefinitionOid());
            return d == null ? Integer.MAX_VALUE : d.getOrdinal();
        }));

        // Subject is signed iff the study_subject row's status is SIGNED.
        // The legacy SignStudySubjectServlet sets BOTH the study_subject
        // status and every event's subject_event_status_id to SIGNED in
        // one transaction (see SignStudySubjectServlet#processRequest →
        // signSubjectEvents + studySub.setStatus(Status.SIGNED)), so a
        // SIGNED study_subject is the authoritative indicator. We do NOT
        // additionally require every event to also report SIGNED — that
        // would re-derive the same fact from a less reliable source and
        // make the flag flicker if event statuses get reset (e.g. a CRF
        // mid-edit). Per the brief's converse guard: an unsigned
        // study_subject row never reports signed regardless of events.
        boolean signed = ss.getStatus() != null && ss.getStatus().equals(Status.SIGNED);

        return new SubjectListItemDto(
                ss.getLabel(),
                secondaryId,
                study.getOid(),
                study.getName(),
                gender,
                yearOfBirth,
                /* groupLabel */ null,
                enrolledOn,
                eventCells,
                signed,
                subjectOpenQueries
        );
    }

    /** Lookup helper for the cache reverse-lookup during cell ordering. */
    private static StudyEventDefinitionBean findDefinitionByOid(
            Map<Integer, StudyEventDefinitionBean> cache, String oid) {
        for (StudyEventDefinitionBean def : cache.values()) {
            if (def != null && oid.equals(def.getOid())) return def;
        }
        return null;
    }

    /**
     * Single-shot open-query aggregation for an entire study.
     *
     * <p>The legacy {@code DiscrepancyNoteDAO#findByStudyEvent} query joins
     * {@code discrepancy_note → dn_item_data_map → item_data → event_crf
     * → study_event}, but groups by {@code resolution_status_id} and
     * scopes to one event at a time. For the matrix we'd issue 21
     * queries to fill the cells in the demo seed and many more in
     * production studies — pure N×M waste.
     *
     * <p>Instead we issue one query per study, returning {@code (study_event_id,
     * count)} pairs for parent notes whose resolution status is open
     * (1 New / 2 Updated / 3 Resolution Proposed). Closed (4) and Not
     * Applicable (5) are excluded — matches the legacy "open queries"
     * definition the JSP uses.
     *
     * <p>Inline SQL because:
     * <ol>
     *   <li>The legacy DAO only exposes per-event variants — there's no
     *       findOpenItemDataByStudy method to wrap.</li>
     *   <li>Adding one to {@code DiscrepancyNoteDAO} is out of scope for
     *       M2 (the plan says "wraps an existing DAO method" — refactoring
     *       the DAO surface belongs in a separate slice).</li>
     * </ol>
     */
    private Map<Integer, Integer> loadOpenQueryCountsForStudy(int studyId) {
        String sql = "SELECT se.study_event_id, COUNT(DISTINCT dn.discrepancy_note_id) "
                + "FROM study_event se "
                + "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "JOIN event_crf ec ON ec.study_event_id = se.study_event_id "
                + "JOIN item_data id_ ON id_.event_crf_id = ec.event_crf_id "
                + "JOIN dn_item_data_map didm ON didm.item_data_id = id_.item_data_id "
                + "JOIN discrepancy_note dn ON dn.discrepancy_note_id = didm.discrepancy_note_id "
                + "WHERE ss.study_id = ? "
                + "  AND dn.parent_dn_id IS NULL "
                + "  AND dn.resolution_status_id IN (1, 2, 3) "
                + "GROUP BY se.study_event_id";
        Map<Integer, Integer> counts = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            // Degrade gracefully — if the aggregation fails (e.g. DB hiccup)
            // the matrix still renders with openQueries=0 for every cell
            // rather than 500ing the entire page. Logged at WARN so it
            // surfaces in monitoring without spamming.
            LOG.warn("Open-query aggregation failed for study {} — falling back to zeros", studyId, e);
        }
        return counts;
    }

    /** Map the single-char DB encoding to the SPA's `Gender` union. */
    private static String mapGender(char g) {
        return switch (g) {
            case 'f', 'F' -> "F";
            case 'm', 'M' -> "M";
            case 'o', 'O' -> "O";
            default -> "U";
        };
    }

    /**
     * Map {@code subject_event_status_id} → SPA {@code EventStatus} union.
     *
     * <p>The SPA union has six values; the bean enum has nine. Stopped /
     * Skipped don't render in the matrix (they're a follow-up workflow);
     * Invalid shouldn't occur in production data. All three collapse to
     * "not-scheduled" — a safe default that produces an empty grey cell
     * rather than a confusing label.
     */
    private static String mapSubjectEventStatus(int subjectEventStatusId) {
        return switch (subjectEventStatusId) {
            case 1 -> "scheduled";
            case 2 -> "not-scheduled";
            case 3 -> "in-progress";
            case 4 -> "complete";
            case 7 -> "locked";
            case 8 -> "signed";
            default -> "not-scheduled";
        };
    }

    /** Extract YoB if the study collects DoB and the subject has one. */
    private static Integer extractYear(SubjectBean subj) {
        if (subj == null || !subj.isDobCollected() || subj.getDateOfBirth() == null) return null;
        // SubjectDAO returns java.sql.Date for the date_of_birth column, and
        // java.sql.Date#toInstant() throws UnsupportedOperationException by
        // design (the sql Date has no time component). Convert via epoch ms.
        return java.time.Instant.ofEpochMilli(subj.getDateOfBirth().getTime())
                .atZone(ZoneId.systemDefault())
                .getYear();
    }

    /** Format `Date` → ISO YYYY-MM-DD. */
    private static String formatIsoDate(Date d) {
        if (d == null) return null;
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault()).toString();
    }
}
