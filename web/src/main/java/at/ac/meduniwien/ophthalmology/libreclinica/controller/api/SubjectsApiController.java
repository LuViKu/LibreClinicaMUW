/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
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
 * Phase E.4 — Subject Matrix adapter (slice #1).
 *
 * <p>Exposes the Investigator's Subject Matrix at
 * {@code GET /pages/api/v1/subjects} as JSON for the Vue SPA. The
 * legacy JSP path at {@code /ListStudySubjects} keeps working in
 * parallel during the bake-in window per DR-018.
 *
 * <p><strong>Scope of this first slice (identity columns only):</strong>
 * {@code id}, {@code secondaryId}, {@code siteOid}, {@code siteLabel},
 * {@code gender}, {@code yearOfBirth}, {@code enrolledOn}. The
 * per-event status grid, open-query counts, and sign-off state are
 * deferred to a follow-up adapter that walks the EventCRF /
 * StudyEvent / DiscrepancyNote tables (each is its own non-trivial
 * aggregation). Until that lands the SPA renders the identity
 * columns from the real DB and the per-event columns are empty —
 * exactly the same shape the SPA already handles when a subject has
 * no scheduled events.
 *
 * <p><strong>Active study selection:</strong> reads from
 * {@code session.getAttribute("study")} which is set by the legacy
 * {@code /MainMenu} flow. SPA-first study selection is a Phase E.4
 * follow-up; until then the user must visit /MainMenu once after
 * login to bind the session-scoped study. If no study is bound the
 * endpoint returns {@code 400 Bad Request}.
 *
 * <p><strong>Authorization:</strong> The chain-level
 * {@code .anyRequest().hasRole("USER")} rule in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig}
 * gates this controller. Per-site filtering for non-investigator
 * roles is handled implicitly via the legacy
 * {@link StudySubjectDAO#findAllByStudyId(int)} call's study scoping;
 * cross-site authorization belongs in the follow-up that adds
 * per-event status (where site OIDs become visible).
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

        List<StudySubjectBean> rows = studySubjectDAO.findAllByStudyId(currentStudy.getId());
        if (rows == null) rows = Collections.emptyList();

        // Cache SubjectBean lookups so a study with multiple participations
        // for the same person doesn't re-hit subject table N times.
        Map<Integer, SubjectBean> subjectCache = new HashMap<>();

        List<SubjectListItemDto> out = new ArrayList<>(rows.size());
        for (StudySubjectBean ss : rows) {
            SubjectBean subj = subjectCache.computeIfAbsent(ss.getSubjectId(), subjectDAO::findByPK);
            out.add(toDto(ss, subj, currentStudy));
        }

        LOG.debug("Subject Matrix adapter served {} rows for study {} (user {})",
                out.size(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.ok(out);
    }

    private SubjectListItemDto toDto(StudySubjectBean ss, SubjectBean subj, StudyBean study) {
        String secondaryId = (ss.getSecondaryLabel() == null || ss.getSecondaryLabel().isBlank())
                ? null : ss.getSecondaryLabel();
        String gender = mapGender(subj == null ? '\0' : subj.getGender());
        Integer yearOfBirth = extractYear(subj);
        String enrolledOn = formatIsoDate(ss.getEnrollmentDate());

        return new SubjectListItemDto(
                ss.getLabel(),
                secondaryId,
                study.getOid(),
                study.getName(),
                gender,
                yearOfBirth,
                /* groupLabel */ null,
                enrolledOn,
                /* events */ Collections.emptyList(),
                /* signed */ false,
                /* openQueries */ 0
        );
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

    /** Extract YoB if the study collects DoB and the subject has one. */
    private static Integer extractYear(SubjectBean subj) {
        if (subj == null || !subj.isDobCollected() || subj.getDateOfBirth() == null) return null;
        return subj.getDateOfBirth()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .getYear();
    }

    /** Format `Date` → ISO YYYY-MM-DD. */
    private static String formatIsoDate(Date d) {
        if (d == null) return null;
        return LocalDate.ofInstant(d.toInstant(), ZoneId.systemDefault()).toString();
    }
}
