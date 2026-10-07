/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * The endpoint matrix: one {@link CrossSiteIsolationSupport.Case} per
 * {@code controller/api} handler that can reach a site's records, plus the
 * list of handlers that are out of scope and why.
 *
 * <p>A case's key is {@code Controller#method}; a case can carry a variant
 * after a {@code |} (the same handler called with another kind of record).
 * {@code CrossSiteIsolationDatabaseIT#everyEndpointIsInTheMatrixOrExplicitlyOutOfScope}
 * checks the keys against the reflected inventory.
 */
final class CrossSiteIsolationMatrix extends CrossSiteIsolationSupport {

    private CrossSiteIsolationMatrix() {}

    private static final String V1 = "/api/v1";

    /** Controllers none of whose handlers reach a site's clinical records, with the reason. */
    static final Map<String, String> OUT_OF_SCOPE_CONTROLLERS = new TreeMap<>(Map.ofEntries(
            Map.entry("AdminApiController", "system administration (password policy, config, cluster); admin role, no site data"),
            Map.entry("AdminMailApiController", "system administration (test e-mail); admin role"),
            Map.entry("AuthApiController", "logout; no record id"),
            Map.entry("BugReportApiController", "free-text report to the maintainers; no record id"),
            Map.entry("ContactApiController", "free-text contact form; no record id"),
            Map.entry("CrfsApiController", "the CRF library is global (not site-owned); design role only"),
            Map.entry("EventCancelReasonsApiController", "static pick-list"),
            Map.entry("EventCrfMigrationApiController", "study-wide CRF version migration; director/admin design operation keyed on a CRF, not a site"),
            Map.entry("JobsAdminApiController", "system administration (background jobs)"),
            Map.entry("LoginHistoryApiController", "system administration (login history of accounts)"),
            Map.entry("ModalitiesApiController", "global modality catalogue"),
            Map.entry("OphthFieldCatalogApiController", "static field catalogue"),
            Map.entry("ResponseSetsApiController", "global response-set library"),
            Map.entry("RuleExpressionApiController", "rule authoring (study design)"),
            Map.entry("RulesApiController", "rule sets are study-design objects, defined once for the study"),
            Map.entry("RulesImportApiController", "rule authoring (study design)"),
            Map.entry("StudiesAdminApiController", "system administration (all studies); admin role"),
            Map.entry("SystemHealthApiController", "system administration (storage, uploaders)"),
            Map.entry("TerminologyApiController", "global terminology dictionary"),
            Map.entry("UploaderHeartbeatApiController", "uploader device heartbeat; no record id"),
            Map.entry("UsersApiController", "user administration (account management), not site clinical data"),
            Map.entry("MeApiController", "own account/session; setActiveStudy is exercised by the session-tampering tests"),
            Map.entry("DicomIngestApiController", "machine ingest endpoint (internal), no session; see unauthenticated probes"),
            Map.entry("DicomWorklistApiController", "device endpoint without a session, so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("OptomedWorklistApiController", "device endpoint without a session, so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("PublicBcvaEntryController", "unauthenticated portal (permitAll behind the reverse proxy), so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("PublicImageUploadController", "unauthenticated portal (permitAll behind the reverse proxy), so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("PublicOctUploadController", "unauthenticated portal (permitAll behind the reverse proxy), so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("PublicUploadController", "unauthenticated portal (permitAll behind the reverse proxy), so there is no site to scope to; probed in CrossSiteIsolationLeaksIT"),
            Map.entry("ImportApiController", "ODM bulk import: study derived from the uploaded document; not exercised here (see report)")));

    /** Individual handlers of otherwise in-scope controllers that are out of scope, with the reason. */
    static final Map<String, String> OUT_OF_SCOPE_HANDLERS = new TreeMap<>(Map.ofEntries(
            Map.entry("SubjectsApiController#create", "creates in the session's own study; no foreign id (label uniqueness is study-wide by design)"),
            Map.entry("SubjectsApiController#checkLabel", "label-uniqueness probe; uniqueness is study-wide by design"),
            Map.entry("StudiesApiController#create", "creates a new top-level study (sysadmin); no foreign id")));

    /**
     * Study-design controllers keyed on a study oid. Their read handlers appear in the matrix (a site user
     * naming another site's oid); the remaining handlers configure the study, need the director or admin role
     * and carry no site-owned record, so they are out of scope.
     */
    static final Set<String> STUDY_DESIGN_CONTROLLERS = Set.of(
            "EventDefinitionsApiController", "GroupClassesApiController", "ImagingModalitiesApiController",
            "StudyModuleEnrollmentApiController", "StudyParametersApiController", "StudySettingsApiController",
            "BuildStudyApiController");


    /**
     * Refusals the suite found missing, as {@code key} (every role and direction) or
     * {@code key|ROLE|A->B}. They run in {@link CrossSiteIsolationLeaksIT}, not in the gate.
     * Empty since the 2026-10 fixes: every authenticated case is in the gate. A new finding
     * is listed here until its guard exists.
     */
    static final Set<String> KNOWN_LEAKS = new java.util.TreeSet<>();

    static boolean isKnownLeak(String name) {
        if (KNOWN_LEAKS.contains(name)) return true;
        int last = name.lastIndexOf('|');
        int prev = name.lastIndexOf('|', last - 1);
        return KNOWN_LEAKS.contains(name.substring(0, prev));
    }

    static void define() {
        if (!CASES.isEmpty()) return;
        subjects();
        eventsAndCrfs();
        retinal();
        ingest();
        patientsAndSearch();
        notesAuditSdv();
        exportsAndDatasets();
        studyScoped();
    }

    /* ---------------------------------------------------------------------- */

    private static AbstractMockHttpServletRequestBuilder<?> rethrow(Callable<AbstractMockHttpServletRequestBuilder<?>> body) {
        try {
            return body.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String eventDefinitionOid() {
        try (java.sql.Connection cn = DATA_SOURCE.getConnection();
             java.sql.Statement st = cn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT oc_oid FROM study_event_definition WHERE study_event_definition_id = 1")) {
            rs.next();
            return rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shaOf(int ingestId) {
        try (java.sql.Connection cn = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = cn.prepareStatement("SELECT sha256 FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setInt(1, ingestId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AbstractMockHttpServletRequestBuilder<?> bindNeutral(String i, Fx target, Fx itemOwner) {
        return rethrow(() -> {
            int item = itemOwner == null ? neutralUnboundItem() : itemOwner.ingestUnbound;
            return json(post(i + item + "/bind"), "{\"studySubjectId\":" + target.ss + ",\"studyEventId\":"
                    + target.event + ",\"eventCrfId\":" + target.eventCrf + ",\"laterality\":\"OD\"}");
        });
    }

    private static void subjects() {
        String s = V1 + "/subjects";
        c("SubjectsApiController#list", f -> get(s)).list();
        c("SubjectsApiController#getOne", f -> get(s + "/" + f.oid));
        c("SubjectsApiController#cameraWorklist", f -> get(s + "/" + f.oid + "/worklist"));
        c("SubjectsApiController#preflightForSign", f -> get(s + "/" + f.oid + "/preflightForSign"));
        c("SubjectsApiController#getOne|by-label", f -> get(s + "/" + f.label));
        c("SubjectsApiController#sign", f -> json(post(s + "/" + f.oid + "/sign"),
                "{\"password\":\"pw\",\"attestation\":true}")).write().noControl("signing needs a complete, signable CRF set");
        c("SubjectsApiController#update", f -> json(put(s + "/" + f.label), "{\"secondaryId\":\"ISOHACK\",\"gender\":\"f\"}")).write();
        c("SubjectsApiController#replaceGroups", f -> json(put(s + "/" + f.label + "/groups"), "{\"assignments\":[]}")).write();
        c("SubjectsApiController#remove", f -> post(s + "/" + f.label + "/remove")).write();
        c("SubjectsApiController#restore", f -> post(s + "/" + f.label + "/restore")).write()
                .noControl("the fixture subject is not removed, so restore is a 409 for the owner");
        c("SubjectsApiController#lock", f -> post(s + "/" + f.label + "/lock")).write();
        c("SubjectsApiController#unlock", f -> post(s + "/" + f.label + "/unlock")).write()
                .noControl("the fixture subject is not locked, so unlock is a 409 for the owner");
        c("SubjectsApiController#matchPreflight", f -> json(post(s + "/match-preflight"),
                "{\"firstName\":\"Anna\",\"lastName\":\"" + f.label.replace("-", "")
                        + "\",\"dateOfBirth\":\"1950-05-05\",\"label\":\"" + f.label + "\"}"))
                .list();
    }

    private static void eventsAndCrfs() {
        String e = V1 + "/events";
        String from = java.time.LocalDate.now().minusDays(30).toString();
        String to = java.time.LocalDate.now().plusDays(30).toString();
        c("EventsApiController#list", f -> get(e + "?subjectId=" + f.label)).list();
        c("EventsApiController#due", f -> get(e + "/due?from=" + from + "&to=" + to + "&studyOid=" + f.site.oid)).list();
        c("EventsApiController#getEventDetail", f -> get(e + "/" + f.event));
        c("EventsApiController#update", f -> json(put(e + "/" + f.event),
                "{\"dateStarted\":\"" + to + "\",\"location\":\"ISOHACK\"}")).write();
        c("EventsApiController#cancel", f -> json(delete(e + "/" + f.event), "{\"reasonCode\":\"PATIENT_NO_SHOW\"}")).write();
        c("EventsApiController#restore", f -> post(e + "/" + f.event + "/restore")).write()
                .noControl("the fixture visit is not cancelled, so restore is a 409 for the owner");
        c("EventsApiController#sign", f -> json(post(e + "/" + f.event + "/sign"),
                "{\"password\":\"pw\",\"attestation\":true}")).write().noControl("signing needs a complete visit");
        c("EventsApiController#startEventCrf", f -> json(post(e + "/" + f.event + "/crfs/2:start"), "{}")).write()
                .noControl("the fixture visit already has its CRF, so start is a 409 for the owner");
        c("EventsApiController#schedule", f -> json(post(e),
                "{\"subjectId\":\"" + f.label + "\",\"eventDefinitionOid\":\"" + eventDefinitionOid() + "\","
                        + "\"dateStarted\":\"2026-02-05\"}")).write();

        String ec = V1 + "/eventCrfs/";
        c("EventCrfsApiController#getEventCrf", f -> get(ec + f.eventCrf));
        c("EventCrfsApiController#saveItems", f -> json(post(ec + f.eventCrf + "/items"),
                "{\"values\":{\"I_HEIGHT_CM\":\"181\"},\"reasons\":{\"I_HEIGHT_CM\":\"checked\"}}")).write();
        c("EventCrfsApiController#markComplete", f -> post(ec + f.eventCrf + "/markComplete")).write();
        c("EventCrfsApiController#markIncomplete", f -> post(ec + f.eventCrf + "/markIncomplete")).write();
        c("EventCrfsApiController#previousValues", f -> get(ec + f.eventCrf + "/previous-values"))
                .noControl("the fixture subject has no earlier completed CRF (404 for the owner)");
        c("EventCrfsApiController#lockStatus", f -> get(ec + f.eventCrf + "/lock-status"));
        c("EventCrfsApiController#heartbeat", f -> post(ec + f.eventCrf + "/heartbeat")).write();
        c("EventCrfsApiController#notesRollup", f -> get(ec + f.eventCrf + "/notes"));
        c("EventCrfsApiController#sectionStatus", f -> get(ec + f.eventCrf + "/section-status"));
        c("EventCrfsApiController#restore", f -> post(ec + f.eventCrf + "/restore")).write()
                .noControl("the fixture CRF is not removed, so restore is a 409 for the owner");
        c("EventCrfsApiController#addGroupRow", f -> post(ec + f.eventCrf + "/groups/IG_DEMOGRAPHICS/rows")).write()
                .noControl("the demographics CRF has no repeating group");
        c("EventCrfsApiController#deleteGroupRow", f -> delete(ec + f.eventCrf + "/groups/IG_DEMOGRAPHICS/rows/2")).write()
                .noControl("the demographics CRF has no repeating group");
        c("EventCrfsApiController#uploadItemFile", f -> multipart(ec + f.eventCrf + "/items/I_CONSENT_DATE/file")
                .file(new MockMultipartFile("file", "source.pdf", "application/pdf", new byte[] {1, 2, 3}))).write()
                .noControl("file storage is mocked and the item is not a file item");
        c("EventCrfsApiController#deleteItemFile", f -> delete(ec + f.eventCrf + "/items/I_CONSENT_SIGNED/file")).write();
        c("EventCrfsApiController#downloadItemFile", f -> get(ec + f.eventCrf + "/items/I_CONSENT_SIGNED/file"));
        c("EventCrfsApiController#getDdePass", f -> get(ec + f.eventCrf + "/dde-pass"))
                .noControl("double data entry is off for the demo definition (409 for the owner)");
        c("EventCrfsApiController#commitDdePass2", f -> json(post(ec + f.eventCrf + "/dde-commit"),
                "{\"values\":{\"I_HEIGHT_CM\":\"170\"}}")).write()
                .noControl("double data entry is off for the demo definition (409 for the owner)");
        c("EventCrfsApiController#getDdeConflicts", f -> get(ec + f.eventCrf + "/dde-conflicts"))
                .noControl("double data entry is off for the demo definition (409 for the owner)");
        c("EventCrfsApiController#resolveDdeConflict", f -> json(post(ec + f.eventCrf + "/dde-conflicts/I_HEIGHT_CM/resolve"),
                "{\"winner\":\"ide\",\"reasonForChange\":\"checked\"}")).write()
                .noControl("double data entry is off for the demo definition (409 for the owner)");
        c("EventCrfsApiController#autoPopulateRetinal", f -> post(ec + f.eventCrf + ":autoPopulateRetinal")).write();
        c("EventCrfRemovalApiController#removalImpact", f -> get(ec + f.eventCrf + "/removal-impact"));
        c("EventCrfRemovalApiController#remove", f -> json(post(ec + f.eventCrf + "/remove"), "{\"reason\":\"x\"}")).write();

        // nAMD clinical flags (stored as item data of the visit CRF) and decisions.
        c("NamdClinicalApiController#listBcvaTimeline", f -> get(V1 + "/study-subjects/" + f.ss + "/bcva-timeline")).list()
                .noControl("the fixture holds no BCVA data");
        c("NamdClinicalApiController#listCrtTimeline", f -> get(V1 + "/study-subjects/" + f.ss + "/crt-timeline")).list()
                .noControl("the CRT compute service is a mock here");
        c("NamdClinicalApiController#listNamdClinicalFlagsTimeline",
                f -> get(V1 + "/study-subjects/" + f.ss + "/namd-clinical-flags")).list()
                .noControl("the fixture holds no clinical flags");
        c("NamdClinicalApiController#upsertNamdClinicalFlags",
                f -> json(post(V1 + "/study-events/" + f.namdEvent + "/namd-clinical-flags"),
                        "{\"od\":{\"hemorrhage\":true},\"os\":{\"hemorrhage\":false}}")).write();
        c("EventCrfsApiController#saveItems|namd-decision", f -> json(post(ec + f.namdEventCrf + "/items"),
                "{\"values\":{\"I_NAMD_DECISION_ACTION\":\"WAIT\",\"I_NAMD_DECISION_DRUG\":\"AFLIBERCEPT\"}}")).write();
    }

    private static void retinal() {
        String r = V1 + "/retinal-jobs/";
        c("RetinalResultsApiController#getJob", f -> get(r + f.job));
        c("RetinalResultsApiController#getJobBySubjectSeq", f -> get(V1 + "/subjects/" + f.label + "/retinal-jobs/1"));
        c("RetinalResultsApiController#listByEventCrf", f -> get(V1 + "/event-crfs/" + f.eventCrf + "/retinal-jobs")).list()
                .ownContains(f -> "\"jobId\":" + f.job);
        c("RetinalResultsApiController#listByStudySubject", f -> get(V1 + "/study-subjects/" + f.ss + "/retinal-jobs")).list()
                .ownContains(f -> "\"jobId\":" + f.job);
        c("RetinalResultsApiController#trendsForSubject",
                f -> get(V1 + "/study-subjects/" + f.ss + "/retinal-trends?task=fluid")).list()
                .ownContains(f -> "\"jobId\":" + f.job);
        c("RetinalResultsApiController#compareToPrevious", f -> get(r + f.job + "/compare-previous"))
                .noControl("the fixture subject has no earlier job to compare with");
        c("RetinalResultsApiController#listParkedJobs", f -> get(V1 + "/retinal-jobs?status=parked")).list()
                .noControlRoleGated("parked jobs are not attributed to a site until bound");
        c("RetinalResultsApiController#retryJob", f -> post(r + f.jobFailed + "/retry")).write()
                .noControl("the remote GPU sidecar is not configured in this IT (409 for the owner)");
        c("RetinalResultsApiController#retryJob|not-failed", f -> post(r + f.job + "/retry")).write()
                .noControl("a done job cannot be retried by anyone; the question is only what a foreign caller learns")
                .note("the guard runs after the status check");
        c("RetinalResultsApiController#rerunAs", f -> json(post(r + f.jobFailed + "/rerun-as"), "{\"task\":\"onl\"}")).write()
                .noControl("the remote GPU sidecar is not configured in this IT (409 for the owner)");
        c("RetinalResultsApiController#bindParkedJob", f -> rethrow(() ->
                json(patch(r + neutralParkedJob() + "/bind"), "{\"eventCrfId\":" + f.eventCrf + "}"))).write();
        c("RetinalResultsApiController#bulkBindParkedJobs", f -> rethrow(() ->
                json(post(r + "bulk-bind"), "{\"jobIds\":[" + neutralParkedJob() + "],\"eventCrfId\":" + f.eventCrf + "}")))
                .write().ownOk(x -> x.ok() && x.body.contains("\"bound\":1"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"bound\":0"));

        // Parked jobs of a staff upload: placed by their ingest item's origin study.
        c("RetinalResultsApiController#bindParkedJob|origin-parked", f -> rethrow(() ->
                json(patch(r + f.parkedJob + "/bind"), "{\"eventCrfId\":" + f.eventCrf + "}"))).write()
                .mixed((own, foreign) -> json(patch(r + foreign.parkedJob + "/bind"),
                        "{\"eventCrfId\":" + own.eventCrf + "}"));
        c("RetinalResultsApiController#bulkBindParkedJobs|origin-parked", f -> rethrow(() ->
                json(post(r + "bulk-bind"), "{\"jobIds\":[" + f.parkedJob + "],\"eventCrfId\":" + f.eventCrf + "}")))
                .write().mixed((own, foreign) -> json(post(r + "bulk-bind"),
                        "{\"jobIds\":[" + foreign.parkedJob + "],\"eventCrfId\":" + own.eventCrf + "}"))
                .ownOk(x -> x.ok() && x.body.contains("\"bound\":1"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"bound\":0"));
        c("RetinalResultsApiController#getJob|parked", f -> get(r + f.parkedJob))
                .noControlRoleGated("a job with no visit has no study, so every non-admin is refused, owner included");
        c("RetinalJobArtifactsApiController#streamArtifact", f -> get(r + f.job + "/artifacts/retina-thickness.csv"));
        c("RetinalJobArtifactsApiController#streamArtifact|companion", f -> get(r + f.job + "/artifacts/fundus.png"));
        c("RetinalJobArtifactsApiController#streamSegmentation", f -> get(r + f.job + "/segmentation"))
                .noControl("the fixture has no segmentation volume to stream (404 for the owner)");
        c("RetinalJobArtifactsApiController#listCorrections", f -> get(r + f.job + "/segmentation/corrections"))
                .ownOk(x -> x.ok());
        c("RetinalJobArtifactsApiController#saveCorrection", f -> json(post(r + f.job + "/segmentation/corrections"),
                "{\"layerIndex\":1,\"layerLabel\":\"ILM\",\"perSliceRows\":{\"0\":[1.0,2.0]}}")).write()
                .noControl("the fixture has no segmentation volume to correct");
        c("RetinalJobArtifactsApiController#deleteCorrection", f -> delete(r + f.job + "/segmentation/corrections/1")).write()
                .noControl("the fixture has no correction to delete (404 for the owner)");
        c("RetinalJobStatusSseController#stream", f -> get(r + f.job + "/status/stream"));
        c("RetinalInferenceApiController#octUpload", f -> multipart(V1 + "/event-crfs/" + f.eventCrf + "/oct-upload")
                .file(new MockMultipartFile("file", "scan.e2e", "application/octet-stream", new byte[] {1, 2, 3}))
                .param("task", "fluid").param("laterality", "OD")).write();
    }

    private static void ingest() {
        String i = V1 + "/ingest/";
        c("IngestInboxApiController#one", f -> get(i + f.ingestBound));
        c("IngestInboxApiController#one|unbound", f -> get(i + f.ingestUnbound));
        c("IngestInboxApiController#preview", f -> get(i + f.ingestBound + "/preview"))
                .noControl("the fixture has no rendered preview (404 for the owner)");
        c("IngestInboxApiController#byEvent", f -> get(i + "by-event/" + f.event)).list();
        c("IngestInboxApiController#counts", f -> get(i + "inbox/counts")).list()
                .noControlRoleGated("aggregate counts of the unbound pool: no per-site data to compare");
        c("IngestInboxApiController#inbox", f -> get(i + "inbox?status=BOUND")).list();
        c("IngestInboxApiController#inbox|unbound", f -> get(i + "inbox")).list();
        c("IngestInboxApiController#inbox|unbound-by-q", f -> get(i + "inbox?q=" + f.label)).list();
        c("IngestInboxApiController#inbox|dismissed", f -> get(i + "inbox?status=DISMISSED")).list();
        c("IngestInboxApiController#bind", f -> bindNeutral(i, f, null)).write();
        c("IngestInboxApiController#bind|foreign-item", f -> bindNeutral(i, f, f))
                .mixed((own, foreign) -> bindNeutral(i, own, foreign)).write();
        c("IngestInboxApiController#bulkBind", f -> rethrow(() -> json(post(i + "bulk-bind"),
                "{\"ids\":[" + neutralUnboundItem() + "],\"studySubjectId\":" + f.ss + ",\"studyEventId\":" + f.event
                        + ",\"eventCrfId\":" + f.eventCrf + "}")))
                .write().ownOk(x -> x.ok() && !x.body.contains("\"bound\":[]"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"bound\":[]"));
        c("IngestInboxApiController#unbind", f -> json(post(i + f.ingestBound + "/unbind"), "{\"dismiss\":false}")).write();
        c("IngestInboxApiController#dismiss", f -> json(post(i + f.ingestUnbound + "/dismiss"), "{\"reason\":\"x\"}")).write();
        c("IngestInboxApiController#restore", f -> post(i + f.ingestDismissed + "/restore")).write();
        c("IngestInboxApiController#bulkDismiss", f -> json(post(i + "bulk-dismiss"),
                "{\"ids\":[" + f.ingestUnbound + "],\"reason\":\"x\"}")).write()
                .ownOk(x -> x.ok() && !x.body.contains("\"dismissed\":[]"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"dismissed\":[]"));

        String g = V1 + "/image-ingest/";
        c("ImageIngestApiController#inbox", f -> get(g + "inbox")).list();
        c("ImageIngestApiController#preview", f -> get(g + f.ingestBound + "/preview"))
                .noControl("the fixture has no rendered preview (404 for the owner)");
        c("ImageIngestApiController#bind", f -> json(post(g + f.ingestUnbound + "/bind"),
                "{\"studySubjectId\":" + f.ss + ",\"studyEventId\":" + f.event + "}")).write();
        c("ImageIngestApiController#dismiss", f -> json(post(g + f.ingestUnbound + "/dismiss"), "{\"reason\":\"x\"}")).write();

        String u = V1 + "/ingest/upload/";
        c("IngestUploadApiController#resolveStaffUpload", f -> json(post(u + "resolve"),
                "{\"scans\":[{\"patientId\":\"" + f.pid + "\",\"scanDate\":\"" + java.time.LocalDate.now() + "\",\"laterality\":\"OD\"}]}"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"state\":\"nopatient\"")).allowEcho()
                .ownOk(x -> x.ok() && !x.body.contains("\"state\":\"nopatient\""));
        c("IngestUploadApiController#preflightStaffUpload", f -> get(u + "preflight?sha256=" + shaOf(f.ingestBound)))
                .ownOk(x -> x.ok() && x.body.contains("\"exists\":true"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"exists\":false"));
        c("IngestUploadApiController#commitStaffUpload", f -> multipart(u + "commit")
                .file(new MockMultipartFile("file", "scan.jpg", "image/jpeg", jpeg()))
                .param("patientId", f.pid).param("scanDate", java.time.LocalDate.now().toString()).param("studyDate", java.time.LocalDate.now().toString())
                .param("laterality", "OD").param("scanIndex", "0").param("eventCrfId", String.valueOf(f.eventCrf))
                .param("studyEventId", String.valueOf(f.event))).write();
        c("IngestUploadApiController#undoStaffUploadItem", f -> delete(u + "items/" + f.ingestFresh)).write();
        c("IngestUploadApiController#undoStaffUploadJob", f -> delete(u + "jobs/" + f.jobFresh)).write();
        c("IngestUploadApiController#undoStaffUploadJob|origin-parked", f -> delete(u + "jobs/" + f.parkedJob)).write();
    }

    /** The smallest thing the upload sniffing takes for a JPEG. */
    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0,
                0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9};
    }

    private static void patientsAndSearch() {
        c("PatientsApiController#list", f -> get(V1 + "/patients?search=" + f.site.tag)).list();
        c("PatientsApiController#list|unfiltered", f -> get(V1 + "/patients?pageSize=200")).list();
        c("PatientsApiController#detail", f -> get(V1 + "/patients/" + f.person));
        c("PatientsApiController#measurements", f -> get(V1 + "/patients/" + f.person + "/measurements?modalityCode=IOP&eye=OD"));
        c("StudySubjectSearchApiController#searchSubjects", f -> get(V1 + "/study-subjects/search?q=" + f.site.tag)).list();
        c("StudySubjectLinkPatientController#linkPatient", f -> rethrow(() -> {
            Fx t = newSet(f.site);
            try (java.sql.Connection cn = DATA_SOURCE.getConnection()) {
                exec(cn, "UPDATE study_subject SET patient_uuid = NULL, patient_id = NULL WHERE study_subject_id = " + t.ss);
            }
            return json(post(V1 + "/study-subjects/" + f.ss + "/link-patient"), "{\"targetSubjectId\":" + t.ss + "}");
        })).write()
                .mixed((own, foreign) -> json(post(V1 + "/study-subjects/" + own.ss + "/link-patient"),
                        "{\"targetSubjectId\":" + foreign.ss + "}"))
                .note("the caller's own enrolment (source) linked to another site's (target)");
        c("StudySubjectLinkPatientController#linkPatient|foreign-source", f -> json(post(V1 + "/study-subjects/" + f.ss + "/link-patient"),
                "{\"targetSubjectId\":" + (f.ss + 1) + "}")).write()
                .mixed((own, foreign) -> json(post(V1 + "/study-subjects/" + foreign.ss + "/link-patient"),
                        "{\"targetSubjectId\":" + own.ss + "}"))
                .noControl("the own-site call of this variant is the base case")
                .note("another site's enrolment (source) linked to the caller's own (target)");
        c("SubjectExportApiController#export", f -> json(post(V1 + "/studies/" + f.site.oid + "/subjects/" + f.label + "/export"),
                "{\"format\":\"odm\",\"dryRun\":true}"));
        c("SubjectExportApiController#export|path-mismatch", f -> json(post(V1 + "/studies/" + f.site.oid + "/subjects/" + f.label + "/export"),
                "{\"format\":\"odm\",\"dryRun\":true}"))
                .mixed((own, foreign) -> json(post(V1 + "/studies/" + own.site.oid + "/subjects/" + foreign.label + "/export"),
                        "{\"format\":\"odm\",\"dryRun\":true}"))
                .note("the study in the path is the caller's own site, the subject another site's");
        c("EyeCohortTransitionsApiController#list", f -> get(V1 + "/subjects/" + f.label + "/eye-transitions")).list()
                .noControl("the fixture subject has no transitions");
        c("EyeCohortTransitionsApiController#transitionPreflight",
                f -> get(V1 + "/subjects/" + f.label + "/eyes/OD/transition/preflight?targetStudyOid=S_STUDYX&targetLabel=X-" + f.label))
                .noControlRoleGated("a transition needs a target study the user may work in");
        c("EyeCohortTransitionsApiController#transition", f -> json(post(V1 + "/subjects/" + f.label + "/eyes/OD/transition"),
                "{\"targetStudyOid\":\"S_STUDYX\",\"targetLabel\":\"X-" + f.label + "\",\"reason\":\"x\"}")).write()
                .noControlRoleGated("a transition needs a target study the user may work in");
        c("ModalityBaselinesApiController#list", f -> get(V1 + "/subjects/" + f.label + "/eyes/OD/modality-baselines")).list()
                .noControl("the fixture holds no measurements");
    }

    private static void notesAuditSdv() {
        String d = V1 + "/discrepancies";
        c("DiscrepancyApiController#list", f -> get(d)).list();
        c("DiscrepancyApiController#list|by-subject", f -> get(d + "?subjectId=" + f.label)).list();
        c("DiscrepancyApiController#exportCsv", f -> get(d + "/export.csv")).list();
        c("DiscrepancyApiController#exportCsv|by-subject", f -> get(d + "/export.csv?subjectId=" + f.label)).list();
        c("DiscrepancyApiController#getThread", f -> get(d + "/" + f.note + "/thread"));
        c("DiscrepancyApiController#appendThread", f -> json(post(d + "/" + f.note + "/thread"),
                "{\"newStatus\":\"updated\",\"description\":\"ISOHACK\"}")).write();
        c("DiscrepancyApiController#add", f -> json(post(d), "{\"subjectId\":\"" + f.label
                + "\",\"itemOid\":\"I_HEIGHT_CM\",\"description\":\"ISOHACK\",\"type\":\"query\"}")).write();

        String a = V1 + "/audit";
        c("AuditApiController#list", f -> get(a)).list().bodyFilter(CrossSiteIsolationMatrix::withoutAuditOfTheTestUsers);
        c("AuditApiController#list|by-subject", f -> get(a + "?subjectId=" + f.label)).list()
                .bodyFilter(CrossSiteIsolationMatrix::withoutAuditOfTheTestUsers);
        c("AuditApiController#exportXlsx", f -> get(a + "/export.xlsx?subjectId=" + f.label)).list()
                .bodyFilter(CrossSiteIsolationMatrix::withoutAuditOfTheTestUsers);
        c("AuditApiController#exportXlsx|unfiltered", f -> get(a + "/export.xlsx")).list()
                .bodyFilter(CrossSiteIsolationMatrix::withoutAuditOfTheTestUsers);
        c("AuditApiController#facets", f -> get(a + "/facets")).list().noControlRoleGated("facet values, no per-site data to compare");
        c("AuditApiController#listSystem", f -> get(a + "/system")).list().noControlRoleGated("system audit entries are not site-bound");
        c("AuditApiController#systemFacets", f -> get(a + "/system/facets")).list().noControlRoleGated("facets carry no site marker");

        c("SdvApiController#list", f -> get(V1 + "/sdv")).list();
        c("SdvApiController#verify", f -> json(post(V1 + "/sdv/verify"), "{\"eventCrfOids\":[\"" + f.eventCrf + "\"]}"))
                .write().ownOk(x -> x.ok() && x.body.contains("\"verifiedCount\":1"))
                .alsoRefused(x -> x.ok() && x.body.contains("\"verifiedCount\":0"));
        c("SdvApiController#unverify", f -> rethrow(() -> {
            try (java.sql.Connection cn = DATA_SOURCE.getConnection()) {
                exec(cn, "UPDATE event_crf SET sdv_status = true WHERE event_crf_id = " + f.eventCrf);
            }
            return json(post(V1 + "/sdv/unverify"), "{\"eventCrfOids\":[\"" + f.eventCrf + "\"],\"reason\":\"x\"}");
        })).write().ownOk(x -> x.ok() && !x.body.contains("\"unverifiedCount\":0") && !x.body.contains("\"verifiedCount\":0"))
                .alsoRefused(x -> x.ok() && (x.body.contains("\"unverifiedCount\":0") || x.body.contains("\"verifiedCount\":0")));
    }

    private static void exportsAndDatasets() {
        String x = V1 + "/exports";
        c("ExportJobsApiController#listJobs", f -> get(x)).list().exportOwned().ownContains(f -> "\"id\":" + f.exportJob);
        c("ExportJobsApiController#getJob", f -> get(x + "/" + f.exportJob)).exportOwned();
        c("ExportJobsApiController#downloadJob", f -> get(x + "/" + f.exportJob + "/download")).exportOwned();
        c("ExportJobsApiController#cancelJob", f -> post(x + "/" + f.exportJob + "/cancel")).write().exportOwned()
                .noControl("the fixture job is finished, so cancel is a 409 for the owner");
        c("ExportJobsApiController#listJobsByStudy", f -> get(V1 + "/studies/" + f.site.oid + "/export-jobs")).list().exportOwned()
                .ownContains(f -> "\"id\":" + f.exportJob);
        c("ExportJobsApiController#enqueueExport", f -> json(post(V1 + "/datasets/" + f.dataset + "/exports"),
                "{\"format\":\"csv\"}")).write().exportOwned().noControl("export workers are not running in this IT");
        c("ExportJobsApiController#listSchedules", f -> get(V1 + "/datasets/" + f.dataset + "/schedules")).list().exportOwned()
                .ownContains(f -> "\"id\":" + f.schedule);
        c("ExportJobsApiController#createSchedule", f -> json(post(V1 + "/datasets/" + f.dataset + "/schedules"),
                "{\"format\":\"csv\",\"cronExpression\":\"0 0 3 ? * MON\"}")).write().exportOwned();
        c("ExportJobsApiController#updateSchedule", f -> json(patch(V1 + "/schedules/" + f.schedule),
                "{\"enabled\":false}")).write().exportOwned();
        c("ExportJobsApiController#deleteSchedule", f -> delete(V1 + "/schedules/" + f.schedule)).write().exportOwned();

        String ds = V1 + "/datasets/";
        c("DatasetsApiController#getDataset", f -> get(ds + f.dataset)).exportOwned();
        c("DatasetsApiController#updateDataset", f -> json(put(ds + f.dataset), "{\"name\":\"ISOHACK\"}")).write().exportOwned()
                .noControl("an edit needs a full dataset definition");
        c("DatasetsApiController#removeDataset", f -> delete(ds + f.dataset)).write().exportOwned()
                .noControl("the fixture dataset has no stored definition, so removal answers 500 for the owner");
        c("DatasetsApiController#restoreDataset", f -> rethrow(() -> {
            try (java.sql.Connection cn = DATA_SOURCE.getConnection()) {
                exec(cn, "UPDATE dataset SET status_id = 5 WHERE dataset_id = " + f.dataset);
            }
            return post(ds + f.dataset + "/restore");
        })).write().exportOwned().noControlRoleGated("restoring a dataset needs the admin role (403 for every site role)");
        c("DatasetsApiController#triggerExport", f -> json(post(ds + f.dataset + "/export"), "{\"format\":\"csv\"}")).write()
                .exportOwned().noControl("export workers are not running in this IT");
        c("DatasetsApiController#testFilter", f -> json(post(ds + f.dataset + ":test-filter"), "{\"filters\":[]}")).write()
                .exportOwned().noControl("filter test needs a stored dataset definition");
        c("DatasetsApiController#listDatasets", f -> get(V1 + "/studies/" + f.site.oid + "/datasets")).list().exportOwned();
        c("DatasetsApiController#listFiles", f -> get(V1 + "/studies/" + f.site.oid + "/datasets/" + f.dataset + "/files"))
                .list().exportOwned();
        c("DatasetsApiController#listFiles|path-mismatch", f -> get(V1 + "/studies/" + f.site.oid + "/datasets/" + f.dataset + "/files"))
                .mixed((own, foreign) -> get(V1 + "/studies/" + own.site.oid + "/datasets/" + foreign.dataset + "/files"))
                .list().exportOwned().note("the study in the path is the caller's own site, the dataset another site's");
        c("DatasetsApiController#downloadFile", f -> get(V1 + "/archived-files/" + f.archivedFile + "/download")).exportOwned();
        c("DatasetsApiController#eventTree", f -> get(V1 + "/studies/" + f.site.oid + "/event-tree")).list()
                .noControlRoleGated("design metadata (event definitions), no per-site data to compare");
        c("DatasetsApiController#quickOdm", f -> post(V1 + "/studies/" + f.site.oid + "/datasets:quick-odm")).write()
                .noControl("quick ODM needs the export pipeline");
        c("DatasetsApiController#createDataset", f -> json(post(V1 + "/studies/" + f.site.oid + "/datasets"),
                "{\"name\":\"ISOHACK\",\"description\":\"x\"}")).write().noControl("dataset definitions need items");
    }

    /** Handlers keyed on a study or site oid: the foreign call names the other site's oid. */
    private static void studyScoped() {
        String st = V1 + "/studies/";
        c("StudiesApiController#get", f -> get(st + f.site.oid)).roles(Who.INV, Who.DIR);
        c("StudiesApiController#removalPreview", f -> get(st + f.site.oid + "/removal-preview")).roles(Who.DIR)
                .noControlRoleGated("study removal is sysadmin only");
        c("StudiesApiController#update", f -> json(put(st + f.site.oid), "{\"name\":\"ISOHACK\"}")).write().roles(Who.DIR);
        c("StudiesApiController#disable", f -> json(post(st + f.site.oid + "/disable"), "{}")).write().roles(Who.DIR)
                .noControlRoleGated("study status is not a site director's task");
        c("StudiesApiController#restore", f -> json(post(st + f.site.oid + "/restore"), "{}")).write().roles(Who.DIR)
                .noControlRoleGated("study status is not a site director's task");
        c("StudiesApiController#setStatus", f -> json(post(st + f.site.oid + "/status"), "{\"status\":\"frozen\"}")).write()
                .roles(Who.DIR).noControlRoleGated("study status is not a site director's task");
        c("StudiesApiController#list", f -> get(V1 + "/studies")).list().roles(Who.INV, Who.DIR);
        c("StudyMetadataApiController#metadata", f -> get(st + f.site.oid + "/metadata")).roles(Who.INV, Who.DIR)
                .noControl("metadata needs the rules and resources services");
        c("SitesApiController#list", f -> get(st + "S_DEFAULTS1/sites")).list().roles(Who.INV, Who.DIR);
        c("SitesApiController#update", f -> json(put(st + "S_DEFAULTS1/sites/" + f.site.oid), "{\"name\":\"ISOHACK\"}"))
                .write().roles(Who.DIR).noControlRoleGated("site administration is not a site director's task");
        c("SitesApiController#disable", f -> post(st + "S_DEFAULTS1/sites/" + f.site.oid + "/disable")).write()
                .roles(Who.DIR).noControlRoleGated("site administration is not a site director's task");
        c("SitesApiController#restore", f -> post(st + "S_DEFAULTS1/sites/" + f.site.oid + "/restore")).write()
                .roles(Who.DIR).noControlRoleGated("site administration is not a site director's task");
        c("SitesApiController#create", f -> json(post(st + "S_DEFAULTS1/sites"), "{\"name\":\"ISOHACK\"}")).write()
                .roles(Who.DIR).noControlRoleGated("site administration is not a site director's task");
        // Design metadata read per study oid.
        c("EventDefinitionsApiController#list", f -> get(st + f.site.oid + "/event-definitions")).roles(Who.INV, Who.DIR)
                .list().alsoRefused(x -> x.status == 409 && x.body.contains("top-level study"))
                .noControlRoleGated("event definitions are managed at the top-level study only: a site answers 409 to everyone");
        c("GroupClassesApiController#list", f -> get(st + f.site.oid + "/group-classes")).roles(Who.INV, Who.DIR)
                .list().alsoRefused(x -> x.status == 409 && x.body.contains("top-level study"))
                .noControlRoleGated("group classes are managed at the top-level study only: a site answers 409 to everyone");
        c("ImagingModalitiesApiController#list", f -> get(st + f.site.oid + "/imaging-modalities")).roles(Who.INV, Who.DIR)
                .list().noControlRoleGated("design metadata (imaging modalities), no per-site data to compare");
        c("StudyModuleEnrollmentApiController#list", f -> get(st + f.site.oid + "/modules")).roles(Who.INV, Who.DIR)
                .list().noControl("module enrolment carries no site marker");
        c("StudyParametersApiController#get", f -> get(st + f.site.oid + "/parameters")).roles(Who.INV, Who.DIR);
        c("StudySettingsApiController#get", f -> get(st + f.site.oid + "/settings")).roles(Who.INV, Who.DIR);
        c("BuildStudyApiController#buildStatus", f -> get(st + f.site.oid + "/build-status")).roles(Who.INV, Who.DIR);
    }

    /* ---------------------------------------------------------------------- */
    /* The runner                                                              */
    /* ---------------------------------------------------------------------- */

    /** What each (case, role, direction) answered on its own site, for the positive controls. */
    static final Map<String, Resp> OWN = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Fx> OWN_FX = new java.util.concurrent.ConcurrentHashMap<>();
    /** What each foreign call answered, for the report. */
    static final Map<String, Resp> FOREIGN = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<String, Fx> SHARED = new java.util.concurrent.ConcurrentHashMap<>();

    static void reset() {
        SHARED.clear();
        OWN.clear();
        OWN_FX.clear();
        FOREIGN.clear();
    }

    /** One set per site for the calls that only read. */
    static Fx shared(Site site) throws Exception {
        Fx f = SHARED.get(site.tag);
        if (f == null) {
            f = newSet(site);
            SHARED.put(site.tag, f);
        }
        return f;
    }

    /** Audit rows the iso users wrote while being refused name the foreign label they typed; they are not site data. */
    static String withoutAuditOfTheTestUsers(String body) {
        try {
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(body);
            tools.jackson.databind.JsonNode events = root.get("events");
            if (events == null || !events.isArray()) return body;
            tools.jackson.databind.node.ArrayNode kept = new tools.jackson.databind.ObjectMapper().createArrayNode();
            for (tools.jackson.databind.JsonNode e : events) {
                String actor = e.path("actor").asText("");
                if (!actor.startsWith("iso_")) kept.add(e);
            }
            return kept.toString();
        } catch (Exception notJson) {
            // a workbook flattened to lines: drop the lines the test users wrote
            return java.util.Arrays.stream(body.split("\n")).filter(l -> !l.contains(" iso_"))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    /** The (key|role|direction) names a class runs, chosen by {@code select} and by {@code ISO_ONLY=regex}. */
    static Stream<DynamicNode> run(Predicate<String> select0) {
        define();
        String only = System.getenv("ISO_ONLY");
        Predicate<String> select = only == null || only.isBlank()
                ? select0 : select0.and(n -> java.util.regex.Pattern.compile(only).matcher(n).find());
        List<DynamicNode> containers = new ArrayList<>();
        // Reads first, on records shared across calls; writes after, each on records of its own.
        List<Case> ordered = new ArrayList<>(CASES);
        ordered.sort(Comparator.comparing((Case k) -> k.write));
        for (Case k : ordered) {
            List<DynamicNode> tests = new ArrayList<>();
            boolean any = false;
            for (Who w : k.roles) {
                for (Site home : w == Who.INV ? new Site[] {A, B} : new Site[] {A}) {
                    String name = k.key + "|" + w + "|" + home.tag.substring(3) + "->" + (home == A ? "B" : "A");
                    if (!select.test(name)) continue;
                    any = true;
                    tests.add(DynamicTest.dynamicTest(name, () -> retryingRefusedConnections(() -> attempt(k, w, home, name))));
                }
            }
            if (!any) continue;
            tests.add(DynamicTest.dynamicTest(k.key + " -- positive control", () -> control(k)));
            containers.add(DynamicContainer.dynamicContainer(k.key, tests));
        }
        return containers.stream();
    }

    /** Gives the export jobs, schedules and dataset of {@code f} to {@code userId}. */
    private static void adopt(Fx f, int userId) throws Exception {
        try (java.sql.Connection cn = DATA_SOURCE.getConnection()) {
            exec(cn, "UPDATE export_job SET submitted_by = " + userId + " WHERE id = " + f.exportJob);
            exec(cn, "UPDATE export_schedule SET created_by = " + userId + " WHERE id = " + f.schedule);
            exec(cn, "UPDATE dataset SET owner_id = " + userId + " WHERE dataset_id = " + f.dataset);
            exec(cn, "UPDATE archived_dataset_file SET owner_id = " + userId + " WHERE archived_dataset_file_id = " + f.archivedFile);
        }
    }

    private static int userId(String name) {
        return new at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO(DATA_SOURCE).findByUserName(name).getId();
    }

    /**
     * Docker Desktop's published port now and then refuses a connection for a moment; the call that met it
     * had not reached the database, so it is simply made again.
     */
    private static void retryingRefusedConnections(org.junit.jupiter.api.function.Executable body) throws Throwable {
        for (int attempt = 1; ; attempt++) {
            try {
                body.execute();
                return;
            } catch (Throwable failure) {
                boolean refused = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (String.valueOf(cause.getMessage()).contains("refused")) refused = true;
                }
                if (!refused || attempt >= 4) throw failure;
                Thread.sleep(3000L * attempt);
            }
        }
    }

    private static void attempt(Case k, Who w, Site home, String name) throws Exception {
        Site other = home == A ? B : A;
        MockHttpSession session = loginAs(w, home);
        Fx own = k.write ? newSet(home) : shared(home);
        if (k.exportOwned) adopt(own, userId(user(w, home)));
        Resp o = call(k.req.apply(own), session);
        OWN.put(name, o);
        OWN_FX.put(name, own);
        if (o.status == 500) {
            String[] lines = POSTGRES.getLogs().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1; i >= 0 && sb.length() < 600; i--) {
                if (lines[i].contains("ERROR") || lines[i].contains("DETAIL") || lines[i].contains("STATEMENT")) {
                    sb.insert(0, lines[i].trim() + " | ");
                }
            }
            System.out.println("[isolation] own call answered 500 for " + name + ": " + sb);
        }
        Fx foreign = k.write ? newSet(other) : shared(other);
        if (k.exportOwned) adopt(foreign, userId(user(Who.INV, other)));
        AbstractMockHttpServletRequestBuilder<?> req = k.mixed != null ? k.mixed.apply(own, foreign) : k.req.apply(foreign);
        Map<String, String> before = snapshot();
        Resp r = call(req, session);
        Map<String, String> after = snapshot();
        FOREIGN.put(name, r);
        if (System.getenv("ISO_ONLY") != null && !System.getenv("ISO_ONLY").isBlank()) {
            System.out.println("[isolation] " + name + "\n    own     : " + o.snippet() + "\n    foreign : " + r.snippet());
        }
        boolean wasRefused = refused(k, r, other);
        assertTrue(wasRefused,
                "LEAK " + name + ": a " + w + " of site " + home.tag.substring(3) + " using site "
                        + other.tag.substring(3) + "'s identifiers got [" + r.snippet() + "]; expected 403/404"
                        + (r.ok() && other.markedIn(r.body) ? " -- response carries site-" + other.tag.substring(3)
                                + " data: ..." + other.context(r.body) + "..." : "") + changed(before, after));
        assertEquals(before, after,
                "WRITE LEAK " + name + ": the refused call still changed " + diff(before, after) + " [" + r.snippet() + "]");
    }

    /** True for {@code key|ROLE|HOME->OTHER} of exactly this case (not of a variant {@code key|variant|...}). */
    private static boolean isRunOf(String name, Case k) {
        return name.startsWith(k.key + "|") && name.substring(k.key.length() + 1).split("[|]").length == 2;
    }

    private static void control(Case k) {
        if (k.noControl != null) {
            boolean reachable = false;
            StringBuilder statuses = new StringBuilder();
            for (Map.Entry<String, Resp> e : OWN.entrySet()) {
                if (!isRunOf(e.getKey(), k)) continue;
                statuses.append(e.getValue().status).append(' ');
                if (!(e.getValue().status == 404 && e.getValue().body.isEmpty())) reachable = true;
            }
            System.out.println("[isolation] no positive control for " + k.key + ": " + k.noControl + " (own: " + statuses + ")");
            boolean everyForeignCallRefused = true;
            for (Map.Entry<String, Resp> e : FOREIGN.entrySet()) {
                if (!isRunOf(e.getKey(), k)) continue;
                if (!refused(k, e.getValue(), e.getKey().contains("|A->B") ? B : A)) everyForeignCallRefused = false;
            }
            // A foreign call that was not refused is reported by its own test; this check is about refusals.
            if (!k.roleGatedOnly && everyForeignCallRefused) {
                boolean distinguishes = false;
                for (Map.Entry<String, Resp> e : OWN.entrySet()) {
                    if (!isRunOf(e.getKey(), k)) continue;
                    Resp foreign = FOREIGN.get(e.getKey());
                    if (foreign != null && foreign.status != e.getValue().status) distinguishes = true;
                }
                assertTrue(distinguishes, "no positive control for " + k.key + " and every role got the same status on its own site "
                        + "as on the other: the refusal may be the role's (" + k.noControl + ")");
            }
            assertTrue(reachable || k.emptyNotFoundOk, "no handler answered " + k.key
                    + " (404 without a body for every role): the endpoint is not wired into the IT");
            return;
        }
        StringBuilder seen = new StringBuilder();
        for (Map.Entry<String, Resp> e : OWN.entrySet()) {
            if (!isRunOf(e.getKey(), k)) continue;
            Resp o = e.getValue();
            seen.append(e.getKey()).append('=').append(o.snippet(), 0, Math.min(o.snippet().length(), 120)).append(" ;; ");
            Site homeSite = e.getKey().contains("|A->B") ? A : B;
            boolean ok = k.ownOk.test(o);
            if (ok && k.kind == Kind.LIST) {
                ok = k.ownContains != null ? o.body.contains(k.ownContains.apply(OWN_FX.get(e.getKey())))
                        : homeSite.markedIn(o.body);
            }
            if (ok) return;
        }
        fail("CONTROL FAILED " + k.key + ": no role could use its own site's identifiers, so the refusal proves nothing. "
                + seen);
    }

    /** Every key a matrix case accounts for, variants folded into the handler. */
    static List<String> allKeys() {
        define();
        return CASES.stream().map(k -> k.key.contains("|") ? k.key.substring(0, k.key.indexOf('|')) : k.key).distinct().toList();
    }
}
