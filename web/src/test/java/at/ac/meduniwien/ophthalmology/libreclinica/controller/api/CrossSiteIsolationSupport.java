/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyConfigService;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.discrepancy.DiscrepancyEmailNotifier;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportScheduleRegistrar;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.Mockito;

/**
 * Shared machinery for the cross-site isolation ITs: the two-site world, the
 * site-scoped users and the way their sessions are bound, one MockMvc over
 * every controller under test, and the endpoint matrix.
 *
 * <h2>The question</h2>
 *
 * <p>Default Study (id 1) has two sites, A and B. A user whose every role is
 * on site A must not be able to read or change anything that belongs to site
 * B, through any endpoint of {@code controller/api}. The access contract is
 * {@code SiteVisibilityFilter} (a site session sees exactly its own id) and
 * {@code StudyResourceAccess} (the guards the controllers call).
 *
 * <h2>How a site user's session is bound</h2>
 *
 * <p>{@link #login} reproduces {@code SpaLoginSuccessHandler.bindActiveStudy}
 * (web/filter/SpaLoginSuccessHandler.java:157-189): {@code userBean} is the
 * account, {@code study} the stored active study (a SITE StudyBean) and
 * {@code userRole} {@code ub.getRoleByStudy(site)} raised with
 * {@code Role.max} against the parent study's binding (none for these users).
 * Switching studies afterwards goes through the real
 * {@code POST /api/v1/me/activeStudy} ({@code MeApiController.setActiveStudy},
 * MeApiController.java:322-390), which is also what the session-tampering
 * tests call.
 *
 * <h2>Oracles</h2>
 *
 * <ul>
 *   <li><b>Foreign call</b> (site-A session, site-B identifiers): must answer
 *       403 or 404. A list-like endpoint may instead answer 2xx provided the
 *       body carries nothing of site B; an endpoint whose contract is a 200
 *       with a per-row refusal (SDV verify) says so with an extra predicate.</li>
 *   <li><b>No side effect</b>: a digest of every clinical table before and
 *       after the foreign call must be identical.</li>
 *   <li><b>Positive control</b>: the same call with site-A identifiers must
 *       succeed for at least one role, else the refusal proves nothing.</li>
 *   <li><b>Marker</b>: every string a response could echo about site B
 *       (label, oid, patient uuid, file names, note text) contains {@code ISOB}
 *       or the site-B uuid prefix.</li>
 * </ul>
 */
@SuppressWarnings({"null", "resource"})
abstract class CrossSiteIsolationSupport extends AbstractApiControllerDatabaseIT {

    /* ====================================================================== */
    /* The world                                                               */
    /* ====================================================================== */

    /** A site of Default Study. */
    static final class Site {
        final int id;
        final String oid;
        final String tag;
        /** Uuid prefix every patient / e2e uuid of this site starts with. */
        final String uuidPrefix;

        Site(int id, String oid, String tag, String uuidPrefix) {
            this.id = id;
            this.oid = oid;
            this.tag = tag;
            this.uuidPrefix = uuidPrefix;
        }

        /** What stands around the first marker, to show in a failure. */
        String context(String text) {
            if (text == null) return "";
            String l = text.toLowerCase();
            int i = l.indexOf(tag.toLowerCase());
            if (i < 0) i = l.indexOf(uuidPrefix);
            if (i < 0) return "";
            return text.substring(Math.max(0, i - 70), Math.min(text.length(), i + 90)).replaceAll("\\s+", " ");
        }

        /** True when {@code text} mentions anything that only this site's data carries. */
        boolean markedIn(String text) {
            if (text == null) return false;
            String l = text.toLowerCase();
            return l.contains(tag.toLowerCase()) || l.contains(uuidPrefix);
        }
    }

    /** One complete set of records of a site. */
    static final class Fx {
        Site site;
        String label;
        String oid;
        String pid;
        int ss;
        int person;
        int patientId;
        String patientUuid;
        int event;
        int eventCrf;
        int itemData;
        int attachmentData;
        int namdEvent;
        int namdEventCrf;
        int note;
        int subjectNote;
        long job;
        long jobFailed;
        long jobFresh;
        String e2eUuid;
        String jobSha;
        int ingestBound;
        int ingestUnbound;
        int ingestFresh;
        int ingestDismissed;
        int dataset;
        int archivedFile;
        long exportJob;
        long schedule;
        long audit;

        @Override
        public String toString() {
            return site.tag + "/" + label;
        }
    }

    /** The roles a site user can hold. */
    enum Who {
        INV("inv", "Investigator"), CRC("crc", "coordinator"), MON("mon", "monitor"),
        RA("ra", "ra"), DIR("dir", "director");

        final String code;
        final String roleName;

        Who(String code, String roleName) {
            this.code = code;
            this.roleName = roleName;
        }
    }

    static Site A;
    static Site B;
    /** A second top-level study the multi-study user also works in. */
    static int studyX;
    static String studyXOid;
    static int xSubject;

    static Path fileRoot;
    static Path bscanRoot;

    private static final AtomicInteger SEQ = new AtomicInteger(100);
    private static final AtomicInteger FILES = new AtomicInteger();

    static RetinalArtifactStorageService artifactStore;

    @BeforeAll
    static void seedWorld() throws Exception {
        // Another class of the same JVM left a MockMvc, sessions and fixtures bound to its own database.
        MVC = null;
        synchronized (CrossSiteIsolationSupport.class) {
            SESSIONS.clear();
        }
        CrossSiteIsolationMatrix.reset();
        fileRoot = Files.createTempDirectory("isolation-it-");
        bscanRoot = Files.createDirectories(fileRoot.resolve("bscan-store"));
        final String bscanRootStr = bscanRoot.toString();
        artifactStore = new RetinalArtifactStorageService() {
            @Override
            protected String bscanStorePath() {
                return bscanRootStr;
            }
        };
        try (Connection c = DATA_SOURCE.getConnection()) {
            int a = LifecycleFixtures.insertStudy(c, 1, "ISOA", "ISOA Site", "S_ISOA", 1);
            int b = LifecycleFixtures.insertStudy(c, 1, "ISOB", "ISOB Site", "S_ISOB", 1);
            A = new Site(a, "S_ISOA", "ISOA", "a0000000-0000-4000");
            B = new Site(b, "S_ISOB", "ISOB", "b0000000-0000-4000");
            studyX = LifecycleFixtures.insertStudy(c, null, "STUDYX", "Study X", "S_STUDYX", 1);
            studyXOid = "S_STUDYX";
            for (Site s : new Site[] {A, B}) {
                for (Who w : new Who[] {Who.INV, Who.CRC, Who.MON, Who.RA, Who.DIR}) {
                    String user = user(w, s);
                    LifecycleFixtures.insertUser(c, user, 1);
                    LifecycleFixtures.insertRole(c, user, s.id, w.roleName, 1);
                    exec(c, "UPDATE user_account SET active_study = " + s.id + " WHERE user_name = '" + user + "'");
                }
            }
            // A user with a role on site A and another study.
            LifecycleFixtures.insertUser(c, "iso_multi", 1);
            LifecycleFixtures.insertRole(c, "iso_multi", A.id, "Investigator", 1);
            LifecycleFixtures.insertRole(c, "iso_multi", studyX, "Investigator", 1);
            exec(c, "UPDATE user_account SET active_study = " + A.id + " WHERE user_name = 'iso_multi'");
            xSubject = LifecycleFixtures.insertStudySubject(c, "STUDYX-1", studyX, 1);
        }
    }

    static String user(Who w, Site s) {
        return "iso_" + w.code + "_" + s.tag.substring(3).toLowerCase();
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    /** A fresh, complete set of records at {@code site}; every call makes a new one. */
    static Fx newSet(Site site) throws Exception {
        int n = SEQ.incrementAndGet();
        Fx f = new Fx();
        f.site = site;
        f.label = site.tag + "-" + n;
        f.pid = f.label; // the PatientId of an upload is the study subject's label
        f.oid = "SS_" + f.label.replace("-", "");
        f.patientUuid = String.format("%s-%012d", site.uuidPrefix + "-8000", n);
        f.e2eUuid = String.format("%s-%012d", site.uuidPrefix.substring(0, 1) + "1111111-2222-4333-8444", n);
        Path dir = Files.createDirectories(fileRoot.resolve(site.tag.toLowerCase() + "-" + n));
        try (Connection c = DATA_SOURCE.getConnection()) {
            f.ss = LifecycleFixtures.insertStudySubject(c, f.label, site.id, 1);
            f.person = LifecycleFixtures.insertOne(c,
                    "SELECT subject_id FROM study_subject WHERE study_subject_id = " + f.ss);
            exec(c, "UPDATE subject SET unique_identifier = 'PID-" + f.label + "', gender = 'f', "
                    + "date_of_birth = '1950-05-05', dob_collected = true WHERE subject_id = " + f.person);
            f.patientId = LifecycleFixtures.insertOne(c,
                    "INSERT INTO patient (patient_uuid, created_by) VALUES ('" + f.patientUuid
                            + "', 1) RETURNING patient_id");
            exec(c, "UPDATE study_subject SET patient_id = " + f.patientId + ", patient_uuid = '"
                    + f.patientUuid + "' WHERE study_subject_id = " + f.ss);

            f.event = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (2, " + f.ss + ", 1, now(), 1, 1, now(), "
                            + "3, false, false) RETURNING study_event_id");
            f.eventCrf = LifecycleFixtures.insertOne(c,
                    "INSERT INTO event_crf (study_event_id, crf_version_id, date_interviewed, "
                            + "completion_status_id, status_id, date_completed, owner_id, date_created, "
                            + "study_subject_id, electronic_signature_status, sdv_status) VALUES ("
                            + f.event + ", 1, now(), 1, 1, now(), 1, now(), " + f.ss
                            + ", false, false) RETURNING event_crf_id");
            f.itemData = LifecycleFixtures.insertOne(c,
                    "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                            + "owner_id, ordinal, deleted) VALUES ((SELECT min(item_id) FROM item "
                            + "WHERE oc_oid = 'I_HEIGHT_CM'), " + f.eventCrf + ", 1, '170', now(), 1, 1, false) "
                            + "RETURNING item_data_id");

            // A file attached to the CRF: the item value is the path of a file under the storage root.
            Path attachment = Files.createDirectories(fileRoot.resolve("crf-files")).resolve(f.label + "-attachment.txt");
            Files.writeString(attachment, f.label + " attachment", StandardCharsets.UTF_8);
            f.attachmentData = LifecycleFixtures.insertOne(c,
                    "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, "
                            + "ordinal, deleted) VALUES ((SELECT min(item_id) FROM item WHERE oc_oid = 'I_CONSENT_SIGNED'), "
                            + f.eventCrf + ", 1, '" + attachment.toString().replace('\\', '/') + "', now(), 1, 1, false) "
                            + "RETURNING item_data_id");

            // The nAMD visit: a second visit with the nAMD visit CRF (version 21).
            f.namdEvent = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (2, " + f.ss + ", 99, now(), 1, 1, now(), "
                            + "1, false, false) RETURNING study_event_id");
            f.namdEventCrf = LifecycleFixtures.insertOne(c,
                    "INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, status_id, "
                            + "owner_id, date_created, study_subject_id, electronic_signature_status, sdv_status) "
                            + "VALUES (" + f.namdEvent + ", 21, 1, 1, 1, now(), " + f.ss
                            + ", false, false) RETURNING event_crf_id");
            exec(c, "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, owner_id, "
                    + "ordinal, deleted) SELECT item_id, " + f.namdEventCrf + ", 1, 'TREAT', now(), 1, 1, false "
                    + "FROM item WHERE oc_oid = 'I_NAMD_DECISION_ACTION' LIMIT 1");

            // Discrepancy notes: one on the item, one on the subject.
            f.note = LifecycleFixtures.insertOne(c,
                    "INSERT INTO discrepancy_note (description, discrepancy_note_type_id, resolution_status_id, "
                            + "date_created, owner_id, entity_type, study_id) VALUES ('" + site.tag
                            + " note " + n + "', 3, 1, now(), 1, 'itemData', " + site.id
                            + ") RETURNING discrepancy_note_id");
            exec(c, "INSERT INTO dn_item_data_map (item_data_id, discrepancy_note_id, column_name, activated) "
                    + "VALUES (" + f.itemData + ", " + f.note + ", 'value', true)");
            f.subjectNote = LifecycleFixtures.insertOne(c,
                    "INSERT INTO discrepancy_note (description, discrepancy_note_type_id, resolution_status_id, "
                            + "date_created, owner_id, entity_type, study_id) VALUES ('" + site.tag
                            + " subject note " + n + "', 3, 1, now(), 1, 'studySub', " + site.id
                            + ") RETURNING discrepancy_note_id");
            exec(c, "INSERT INTO dn_study_subject_map (study_subject_id, discrepancy_note_id, column_name) "
                    + "VALUES (" + f.ss + ", " + f.subjectNote + ", 'enrollment_date')");

            // Retinal job, result, artifacts and companions.
            Path seg = Files.createDirectories(dir.resolve("seg"));
            Files.writeString(seg.resolve("retina-thickness.csv"),
                    "x,y,thickness_um\n0,0,260.5\n# " + f.label + "\n", StandardCharsets.UTF_8);
            Path companions = Files.createDirectories(bscanRoot.resolve(f.e2eUuid));
            Files.write(companions.resolve("bscan.dcm"), (f.label + " bscan").getBytes(StandardCharsets.UTF_8));
            Files.write(companions.resolve("fundus.png"), (f.label + " fundus").getBytes(StandardCharsets.UTF_8));
            Files.writeString(companions.resolve("geometry.json"),
                    "{\"axial_mm\":0.004,\"label\":\"" + f.label + "\"}", StandardCharsets.UTF_8);
            f.job = 700000L + n * 10L;
            f.jobSha = sha(site, n, 3);
            exec(c, "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, eye_laterality, "
                    + "status, enqueued_at, completed_at, model_version, e2e_sha256, scan_index) VALUES (" + f.job
                    + ", " + f.eventCrf + ", 'fluid', '" + dir.resolve(f.e2eUuid + ".e2e").toString().replace('\\', '/')
                    + "', 'OD', 'done', now() - interval '1 day', now() - interval '1 day', 'v1', '" + f.jobSha
                    + "', 0)");
            f.jobFailed = f.job + 1;
            Path failedFile = dir.resolve("failed.e2e");
            Files.write(failedFile, (f.label + " failed").getBytes(StandardCharsets.UTF_8));
            exec(c, "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, eye_laterality, "
                    + "status, status_message, enqueued_at, model_version, e2e_sha256, scan_index) VALUES ("
                    + f.jobFailed + ", " + f.eventCrf + ", 'fluid', '" + failedFile.toString().replace('\\', '/')
                    + "', 'OS', 'failed', 'x', now() - interval '1 day', 'v1', '" + sha(site, n, 4) + "', 0)");
            f.jobFresh = f.job + 2;
            Path freshFile = dir.resolve("fresh.e2e");
            Files.write(freshFile, (f.label + " fresh").getBytes(StandardCharsets.UTF_8));
            exec(c, "INSERT INTO retinal_inference_job (job_id, event_crf_id, task, e2e_path, eye_laterality, "
                    + "status, enqueued_at, model_version, e2e_sha256, scan_index) VALUES (" + f.jobFresh + ", "
                    + f.eventCrf + ", 'fluid', '" + freshFile.toString().replace('\\', '/')
                    + "', 'OD', 'queued', now(), 'v1', '" + sha(site, n, 5) + "', 1)");
            exec(c, "INSERT INTO retinal_inference_result (job_id, task, output_payload, primary_metric_value, "
                    + "primary_metric_unit, bscan_masks_dir, confidence) VALUES (" + f.job + ", 'fluid', "
                    + "'{\"biomarkers\":{\"irf_mm3\":1.5},\"label\":\"" + f.label + "\"}'::jsonb, 12.34, 'mm3', '"
                    + seg.toString().replace('\\', '/') + "', 0.87)");

            // Ingest items: one bound to the subject, one still in the inbox naming its patient.
            Path bound = dir.resolve("bound.jpg");
            Files.write(bound, (f.label + " image").getBytes(StandardCharsets.UTF_8));
            f.ingestBound = LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "laterality, received_at, status, bound_study_subject_id, bound_study_event_id, "
                            + "patient_id, sha256, byte_size) VALUES ('image', 'upload', '" + site.tag.toLowerCase()
                            + "-cam', '" + bound.toString().replace('\\', '/') + "', '" + site.tag.toLowerCase() + "-" + n
                            + "-bound.jpg', 'OD', now(), 'BOUND', " + f.ss + ", " + f.event + ", '" + f.pid
                            + "', '" + sha(site, n, 1) + "', 20) RETURNING ingest_item_id");
            Path unbound = dir.resolve("unbound.jpg");
            Files.write(unbound, (f.label + " unbound image").getBytes(StandardCharsets.UTF_8));
            f.ingestUnbound = LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "laterality, received_at, status, patient_id, sha256, byte_size, "
                            + "candidate_study_subject_id) VALUES ('image', 'upload', '" + site.tag.toLowerCase()
                            + "-cam', '" + unbound.toString().replace('\\', '/') + "', '" + site.tag.toLowerCase() + "-" + n
                            + "-unbound.jpg', 'OS', now(), 'UNBOUND', '" + f.pid + "', '" + sha(site, n, 2)
                            + "', 20, " + f.ss + ") RETURNING ingest_item_id");
            Path fresh = dir.resolve("fresh.jpg");
            Files.write(fresh, (f.label + " fresh image").getBytes(StandardCharsets.UTF_8));
            f.ingestFresh = LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "laterality, received_at, status, bound_study_subject_id, bound_study_event_id, "
                            + "match_policy, patient_id, sha256, byte_size) VALUES ('image', 'upload', '"
                            + site.tag.toLowerCase() + "-cam', '" + fresh.toString().replace('\\', '/') + "', '"
                            + site.tag.toLowerCase() + "-" + n + "-fresh.jpg', 'OD', now(), 'BOUND', " + f.ss + ", "
                            + f.event + ", 'visit-picked', '" + f.pid + "', '" + sha(site, n, 6)
                            + "', 20) RETURNING ingest_item_id");
            Path dismissed = dir.resolve("dismissed.jpg");
            Files.write(dismissed, (f.label + " dismissed image").getBytes(StandardCharsets.UTF_8));
            f.ingestDismissed = LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "laterality, received_at, status, patient_id, sha256, byte_size) VALUES ('image', "
                            + "'upload', '" + site.tag.toLowerCase() + "-cam', '" + dismissed.toString().replace('\\', '/')
                            + "', '" + site.tag.toLowerCase() + "-" + n + "-dismissed.jpg', 'OD', now(), 'DISMISSED', '"
                            + f.pid + "', '" + sha(site, n, 7) + "', 20) RETURNING ingest_item_id");

            // Dataset, archived file, export job, schedule — of the site's own study.
            f.dataset = LifecycleFixtures.insertDataset(c, site.id, site.tag + " dataset " + n, 1);
            Path export = dir.resolve("export.csv");
            Files.writeString(export, "subject\n" + f.label + "\n", StandardCharsets.UTF_8);
            f.archivedFile = LifecycleFixtures.insertOne(c,
                    "INSERT INTO archived_dataset_file (name, dataset_id, export_format_id, file_reference, "
                            + "run_time, file_size, date_created, owner_id) VALUES ('" + site.tag.toLowerCase()
                            + "-export-" + n + ".csv', " + f.dataset + ", 1, '" + export.toString().replace('\\', '/')
                            + "', 1, 20, now(), 1) RETURNING archived_dataset_file_id");
            f.exportJob = LifecycleFixtures.insertOne(c,
                    "INSERT INTO export_job (dataset_id, format, status, submitted_by, submitted_at, "
                            + "finished_at, archived_dataset_file_id) VALUES (" + f.dataset
                            + ", 'csv', 'done', 1, now(), now(), " + f.archivedFile + ") RETURNING id");
            f.schedule = LifecycleFixtures.insertOne(c,
                    "INSERT INTO export_schedule (dataset_id, format, cron_expression, active, created_by) "
                            + "VALUES (" + f.dataset + ", 'csv', '0 0 3 * * *', true, 1) RETURNING id");

            // Audit entries.
            f.audit = LifecycleFixtures.insertOne(c,
                    "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                            + "entity_id, entity_name, old_value, new_value, event_crf_id, study_event_id) "
                            + "VALUES (1, now(), 1, 'item_data', " + f.itemData + ", 'I_HEIGHT_CM', '', '"
                            + f.label + "-v', " + f.eventCrf + ", " + f.event + ") RETURNING audit_id");
            exec(c, "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                    + "entity_id, entity_name, old_value, new_value) VALUES (5, now(), 1, 'study_subject', "
                    + f.ss + ", 'label', '', '" + f.label + "')");
        }
        return f;
    }

    private static String sha(Site site, int n, int which) {
        return String.format("%s%063d", site.tag.toLowerCase().charAt(3) == 'a' ? "a" : "b", n * 10 + which);
    }

    /** A parked retinal job (no visit yet) with no site marker, to bind into a target. */
    static long neutralParkedJob() throws Exception {
        int n = SEQ.incrementAndGet();
        long id = 800000L + n;
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "INSERT INTO retinal_inference_job (job_id, task, e2e_path, eye_laterality, status, "
                    + "enqueued_at, model_version) VALUES (" + id + ", 'fluid', '/dev/null/neutral-" + n
                    + ".e2e', 'OD', 'parked', now(), 'v1')");
        }
        return id;
    }

    /** An unbound ingest item that names no patient and no site. */
    static int neutralUnboundItem() throws Exception {
        int n = SEQ.incrementAndGet();
        Path p = fileRoot.resolve("neutral-" + n + ".jpg");
        Files.write(p, ("neutral " + n).getBytes(StandardCharsets.UTF_8));
        try (Connection c = DATA_SOURCE.getConnection()) {
            return LifecycleFixtures.insertOne(c,
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "laterality, received_at, status, sha256, byte_size) VALUES ('image', 'upload', "
                            + "'neutral-cam', '" + p.toString().replace('\\', '/') + "', 'neutral-" + n
                            + ".jpg', 'OD', now(), 'UNBOUND', '" + String.format("c%063d", n)
                            + "', 20) RETURNING ingest_item_id");
        }
    }

    /* ====================================================================== */
    /* Sessions                                                                */
    /* ====================================================================== */

    private static final Map<String, MockHttpSession> SESSIONS = new LinkedHashMap<>();

    /**
     * The session a real login of {@code userName} produces, bound to the
     * account's stored active study. A mirror of
     * {@code SpaLoginSuccessHandler#bindActiveStudy}.
     */
    static synchronized MockHttpSession login(String userName) {
        return SESSIONS.computeIfAbsent(userName, CrossSiteIsolationSupport::freshLogin);
    }

    static MockHttpSession freshLogin(String userName) {
        UserAccountBean ub = new UserAccountDAO(DATA_SOURCE).findByUserName(userName);
        assertTrue(ub.getId() > 0, userName + " exists");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        StudyUserRoleBean role = new StudyUserRoleBean();
        if (ub.getActiveStudyId() > 0) {
            StudyDAO studyDao = new StudyDAO(DATA_SOURCE);
            study = studyDao.findByPK(ub.getActiveStudyId());
            study.setStudyParameters(new StudyParameterValueDAO(DATA_SOURCE).findParamConfigByStudy(study));
            StudyConfigService config = new StudyConfigService(DATA_SOURCE);
            if (study.getParentStudyId() <= 0) {
                config.setParametersForStudy(study);
            } else {
                study.setParentStudyName(studyDao.findByPK(study.getParentStudyId()).getName());
                config.setParametersForSite(study);
            }
            boolean removed = Status.DELETED.equals(study.getStatus())
                    || Status.AUTO_DELETED.equals(study.getStatus());
            if (study.getId() > 0 && !removed) {
                role = ub.getRoleByStudy(study.getId());
                if (study.getParentStudyId() > 0) {
                    StudyUserRoleBean roleInParent = ub.getRoleByStudy(study.getParentStudyId());
                    role.setRole(Role.max(role.getRole(), roleInParent.getRole()));
                }
            }
        }
        session.setAttribute("study", study);
        session.setAttribute("userRole", role);
        return session;
    }

    static MockHttpSession loginAs(Who w, Site s) {
        return login(user(w, s));
    }

    /* ====================================================================== */
    /* MockMvc over every controller under test                                */
    /* ====================================================================== */

    static MockMvc MVC;
    static SiteVisibilityFilter FILTER;

    static synchronized MockMvc mvc() {
        if (MVC != null) return MVC;
        FILTER = new SiteVisibilityFilter(DATA_SOURCE);
        // Updating a study event fires the rule engine through a static application context; give it a
        // context whose rule-set DAO finds no rule sets.
        org.springframework.context.ApplicationContext ctx = Mockito.mock(org.springframework.context.ApplicationContext.class);
        Mockito.when(ctx.getBean("ruleSetDao",
                at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao.class))
                .thenReturn(Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao.class));
        new at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener(
                new at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO(DATA_SOURCE))
                .setApplicationContext(ctx);
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        Mockito.when(remote.isConfigured()).thenReturn(false);
        StudySubjectFinder finder = new StudySubjectFinder(DATA_SOURCE);
        RetinalJobStatusBroadcaster broadcaster = new RetinalJobStatusBroadcaster();
        RetinalInferenceApiController inference = new RetinalInferenceApiController(
                DATA_SOURCE, FILTER, Mockito.mock(RetinalInferenceClient.class), remote, artifactStore,
                Mockito.mock(RetinalMetricComputer.class), broadcaster);
        PublicOctUploadController oct = new PublicOctUploadController(DATA_SOURCE, finder, remote, inference);
        PublicImageUploadController images = new PublicImageUploadController(DATA_SOURCE, finder);
        SecurityManager securityManager = Mockito.mock(SecurityManager.class);
        Mockito.when(securityManager.verifyPassword(Mockito.any(), Mockito.any())).thenReturn(true);
        CrfFileStorageService storage = Mockito.mock(CrfFileStorageService.class);
        Mockito.when(storage.baseDir()).thenReturn(fileRoot);
        ExportScheduleRegistrar registrar = Mockito.mock(ExportScheduleRegistrar.class);
        Mockito.when(registrar.isValidCron(Mockito.anyString())).thenReturn(true);
        NamdClinicalApiController namd = new NamdClinicalApiController(DATA_SOURCE, FILTER);
        namd.setCrtComputeService(Mockito.mock(
                at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.CrtComputeService.class));
        MVC = MockMvcBuilders.standaloneSetup(
                        new MeApiController(DATA_SOURCE),
                        new SubjectsApiController(DATA_SOURCE, securityManager, FILTER),
                        new EventCrfsApiController(DATA_SOURCE, FILTER,
                                storage, new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new EventCrfRemovalApiController(DATA_SOURCE, FILTER),
                        new EventsApiController(DATA_SOURCE, FILTER, new VisitIntervalCalculator(DATA_SOURCE),
                                securityManager),
                        namd,
                        inference,
                        new RetinalResultsApiController(DATA_SOURCE, FILTER, artifactStore, finder, remote,
                                broadcaster, inference),
                        new RetinalJobArtifactsApiController(DATA_SOURCE, FILTER, artifactStore),
                        new RetinalJobStatusSseController(DATA_SOURCE, FILTER, broadcaster),
                        new IngestInboxApiController(DATA_SOURCE, FILTER, finder, remote, inference),
                        new ImageIngestApiController(DATA_SOURCE, FILTER, finder),
                        new IngestUploadApiController(DATA_SOURCE, FILTER, finder, oct),
                        oct, images, new PublicUploadController(DATA_SOURCE, finder, oct, images),
                        new PatientsApiController(DATA_SOURCE, FILTER),
                        new StudySubjectLinkPatientController(DATA_SOURCE, FILTER),
                        new StudySubjectSearchApiController(DATA_SOURCE, FILTER, finder),
                        new SubjectExportApiController(DATA_SOURCE, FILTER),
                        new EyeCohortTransitionsApiController(DATA_SOURCE, FILTER),
                        new ModalityBaselinesApiController(DATA_SOURCE, FILTER),
                        new DiscrepancyApiController(DATA_SOURCE, FILTER,
                                Mockito.mock(DiscrepancyEmailNotifier.class)),
                        new AuditApiController(DATA_SOURCE, FILTER),
                        new SdvApiController(DATA_SOURCE, FILTER),
                        new ExportJobsApiController(DATA_SOURCE, registrar),
                        new DatasetsApiController(DATA_SOURCE, Mockito.mock(CoreResources.class),
                                Mockito.mock(RuleSetRuleDao.class)),
                        new StudiesApiController(DATA_SOURCE),
                        new SitesApiController(DATA_SOURCE),
                        new StudyMetadataApiController(DATA_SOURCE, Mockito.mock(RuleSetRuleDao.class),
                                Mockito.mock(CoreResources.class)),
                        new EventDefinitionsApiController(DATA_SOURCE, remote, inference),
                        new GroupClassesApiController(DATA_SOURCE),
                        new ImagingModalitiesApiController(DATA_SOURCE),
                        new StudyModuleEnrollmentApiController(DATA_SOURCE),
                        new StudyParametersApiController(DATA_SOURCE),
                        new StudySettingsApiController(DATA_SOURCE),
                        new BuildStudyApiController(DATA_SOURCE, FILTER),
                        new DicomWorklistApiController(DATA_SOURCE),
                        new OptomedWorklistApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        return MVC;
    }

    /* ====================================================================== */
    /* Calls and snapshots                                                     */
    /* ====================================================================== */

    /** What a call answered. */
    static final class Resp {
        final int status;
        final String body;
        final String contentType;

        Resp(int status, String body, String contentType) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.contentType = contentType;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String snippet() {
            String b = body.replaceAll("\\s+", " ");
            return status + " " + (b.length() > 260 ? b.substring(0, 260) + "..." : b);
        }
    }

    static Resp call(MockHttpServletRequestBuilder req, MockHttpSession session) throws Exception {
        MockHttpServletResponse r = mvc().perform(req.session(session)).andReturn().getResponse();
        byte[] bytes = r.getContentAsByteArray();
        return new Resp(r.getStatus(), text(bytes), r.getContentType());
    }

    /** The response as searchable text; a zip (an .xlsx) is unpacked and its entries joined. */
    static String text(byte[] bytes) {
        if (bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            StringBuilder sb = new StringBuilder();
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                         new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
                for (org.apache.poi.ss.usermodel.Sheet sheet : wb) {
                    for (org.apache.poi.ss.usermodel.Row row : sheet) {
                        for (org.apache.poi.ss.usermodel.Cell cell : row) {
                            sb.append(cell.toString()).append(' ');
                        }
                        sb.append('\n');
                    }
                }
                return sb.toString();
            } catch (Exception notAWorkbook) {
                // not an xlsx after all
            }
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** One line saying which tables a refused call nevertheless changed, or nothing. */
    static String changed(Map<String, String> before, Map<String, String> after) {
        return before.equals(after) ? "" : " [and it changed tables " + diff(before, after) + "]";
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final String[] DIGEST_TABLES = {
            "subject", "study_subject", "study_event", "event_crf", "item_data", "discrepancy_note",
            "dn_item_data_map", "dn_study_subject_map", "dn_event_crf_map", "dn_study_event_map",
            "retinal_inference_job", "retinal_inference_result", "retinal_inference_correction", "ingest_item",
            "patient", "dataset", "archived_dataset_file", "export_job", "export_schedule",
            "subject_group_map", "study_user_role", "user_account", "study", "study_event_definition",
            "event_definition_crf"};

    /** One md5 per clinical table; two snapshots are equal when nothing was written. */
    static Map<String, String> snapshot() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            for (String t : DIGEST_TABLES) {
                try (ResultSet rs = s.executeQuery("SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) "
                        + "FROM " + t + " x")) {
                    rs.next();
                    out.put(t, rs.getString(1));
                } catch (SQLException missing) {
                    // a table this schema does not have
                }
            }
        }
        return out;
    }

    static String diff(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        for (String t : before.keySet()) {
            if (!before.get(t).equals(after.get(t))) changed.add(t);
        }
        return changed.toString();
    }

    /* ====================================================================== */
    /* The matrix                                                              */
    /* ====================================================================== */

    /** How a refusal looks. */
    enum Kind {
        /** 403 / 404 only. */
        STRICT,
        /** 403 / 404, or a 2xx whose body carries nothing of the other site. */
        LIST
    }

    /** One endpoint, and how to call it for a set of records. */
    static final class Case {
        final String key;
        final Function<Fx, MockHttpServletRequestBuilder> req;
        boolean write;
        Kind kind = Kind.STRICT;
        List<Who> roles = List.of(Who.INV, Who.CRC, Who.MON, Who.RA, Who.DIR);
        Predicate<Resp> ownOk = Resp::ok;
        /** A response that counts as a refusal although it is not 403 / 404. */
        Predicate<Resp> alsoRefused;
        /** Why a positive control could not be built, when none could. */
        String noControl;
        String note = "";
        /** The call for a foreign record that needs both sites' records (own, foreign). */
        java.util.function.BiFunction<Fx, Fx, MockHttpServletRequestBuilder> mixed;
        /** A string the positive control's body must carry instead of the site marker. */
        Function<Fx, String> ownContains;
        /** Drops from a body what the test itself put there (audit rows of the iso users) before it is searched. */
        Function<String, String> bodyFilter;
        /** The response may echo what the caller sent, so the marker check is skipped. */
        boolean allowEcho;
        /** No site role may call the endpoint at all, so its refusal is the role's, not the site's. */
        boolean roleGatedOnly;
        /** A 404 without a body is the handler's own answer (a download that finds no file). */
        boolean emptyNotFoundOk;
        /** The records' export jobs / schedules belong to the caller (own) or to a user of the other site. */
        boolean exportOwned;

        Case(String key, Function<Fx, MockHttpServletRequestBuilder> req) {
            this.key = key;
            this.req = req;
        }

        Case write() { this.write = true; return this; }
        Case list() { this.kind = Kind.LIST; return this; }
        Case roles(Who... w) { this.roles = List.of(w); return this; }
        Case ownOk(Predicate<Resp> p) { this.ownOk = p; return this; }
        Case alsoRefused(Predicate<Resp> p) { this.alsoRefused = p; return this; }
        Case noControl(String why) { this.noControl = why; return this; }
        Case note(String n) { this.note = n; return this; }
        Case mixed(java.util.function.BiFunction<Fx, Fx, MockHttpServletRequestBuilder> m) { this.mixed = m; return this; }
        Case ownContains(Function<Fx, String> f) { this.ownContains = f; return this; }
        Case allowEcho() { this.allowEcho = true; return this; }
        Case emptyNotFoundOk() { this.emptyNotFoundOk = true; return this; }
        Case roleGatedOnly() { this.roleGatedOnly = true; return this; }
        Case noControlRoleGated(String why) { this.noControl = why; this.roleGatedOnly = true; return this; }
        Case bodyFilter(Function<String, String> f) { this.bodyFilter = f; return this; }
        Case exportOwned() { this.exportOwned = true; return this; }
    }

    static final List<Case> CASES = new ArrayList<>();

    static Case c(String key, Function<Fx, MockHttpServletRequestBuilder> req) {
        Case k = new Case(key, req);
        CASES.add(k);
        return k;
    }

    /** Whether {@code foreign} counts as a refusal of {@code c}, for records of {@code other}. */
    static boolean refused(Case c, Resp raw, Site other) {
        Resp r = c.bodyFilter == null ? raw : new Resp(raw.status, c.bodyFilter.apply(raw.body), raw.contentType);
        if (r.status == 403 || r.status == 404) return true;
        if (c.alsoRefused != null && c.alsoRefused.test(r) && (c.allowEcho || !other.markedIn(r.body))) return true;
        return c.kind == Kind.LIST && r.ok() && !other.markedIn(r.body);
    }

    static void assertEqualsTables(Map<String, String> before, Map<String, String> after, String what) {
        assertEquals(before, after, what + " changed tables " + diff(before, after));
    }

    static String read(Path p) throws IOException {
        return Files.readString(p);
    }
}
