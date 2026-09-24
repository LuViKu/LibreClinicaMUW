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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * DR-033 — the uploader heartbeats and the storage scan, against a real
 * PostgreSQL with the production changelog applied.
 *
 * <p>Pinned: a heartbeat creates one row and every later one updates it (an
 * age the program no longer knows does not erase the server's); the three
 * refusals (malformed, oversized, switched off) and the cap on new programs
 * that leaves known ones reporting; the page's classification of fresh,
 * silent and stopped programs and the per-device arrivals; that a program
 * can be forgotten; and that a scan measures the configured stores without
 * counting a nested one twice, keeps 90 days, and turns a week of shrinking
 * free space into days until full.
 */
class SystemHealthDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private final Map<String, String> config = new HashMap<>();

    @BeforeEach
    void clean() throws Exception {
        config.clear();
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM uploader_instance");
            st.executeUpdate("DELETE FROM storage_usage_sample");
            st.executeUpdate("DELETE FROM ingest_item WHERE source_kind = 'it-health'");
        }
    }

    /* ---- wiring ------------------------------------------------------- */

    private MockMvc heartbeatMvc() {
        UploaderHeartbeatApiController c = new UploaderHeartbeatApiController(DATA_SOURCE) {
            @Override
            protected String configField(String key, String fallback) {
                return config.getOrDefault(key, fallback);
            }
        };
        return MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private StorageUsageSampler sampler() {
        return new StorageUsageSampler(DATA_SOURCE) {
            @Override
            protected String configField(String key, String fallback) {
                // Only what the test configured: never the real /var/lib paths.
                return config.getOrDefault(key, "");
            }
        };
    }

    private MockMvc adminMvc(StorageUsageSampler sampler) {
        SystemHealthApiController c = new SystemHealthApiController(DATA_SOURCE, sampler) {
            @Override
            protected String configField(String key, String fallback) {
                return config.getOrDefault(key, fallback);
            }
        };
        return MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        return s;
    }

    private static MockHttpSession physician() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("physician");
        s.setAttribute("userBean", ub);
        return s;
    }

    private static String heartbeat(String uid, String extra) {
        return "{\"instanceId\":\"" + uid + "\",\"kind\":\"export-watcher\",\"name\":\"CLARUS-PC\","
                + "\"version\":\"2026-09-24\",\"heartbeatIntervalSec\":120" + extra + "}";
    }

    private int send(MockMvc mvc, String body) throws Exception {
        return mvc.perform(post("/api/v1/device/uploader/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode getJson(MockMvc mvc, String path) throws Exception {
        String body = mvc.perform(get(path).session(sysadmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static long scalar(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /* ---- heartbeat ---------------------------------------------------- */

    @Test
    void aHeartbeatCreatesOneRowAndLaterOnesUpdateIt() throws Exception {
        MockMvc mvc = heartbeatMvc();
        String uid = UUID.randomUUID().toString();

        assertEquals(200, send(mvc, heartbeat(uid, ",\"secondsSinceUpload\":60,\"pendingFiles\":3")));
        // restarted program: no upload known any more, queue drained
        assertEquals(200, send(mvc, heartbeat(uid, ",\"pendingFiles\":0,\"uploadedToday\":0")));

        assertEquals(1L, scalar("SELECT count(*) FROM uploader_instance"));
        assertEquals(0L, scalar("SELECT pending_files FROM uploader_instance"));
        // the upload the server already knew of is kept
        assertEquals(1L, scalar("SELECT count(*) FROM uploader_instance WHERE last_upload_at IS NOT NULL"));
        assertEquals(1L, scalar("SELECT count(*) FROM uploader_instance "
                + "WHERE last_upload_at BETWEEN now() - interval '2 minutes' AND now() - interval '50 seconds'"));
    }

    @Test
    void malformedOversizedAndSwitchedOffHeartbeatsAreRefused() throws Exception {
        MockMvc mvc = heartbeatMvc();

        assertEquals(400, send(mvc, "{\"instanceId\":\"not-a-uuid\",\"kind\":\"export-watcher\"}"));
        assertEquals(400, send(mvc, "this is not json"));
        String padding = "x".repeat(UploaderHeartbeatApiController.MAX_BODY_BYTES);
        assertEquals(413, send(mvc, heartbeat(UUID.randomUUID().toString(), ",\"pad\":\"" + padding + "\"")));

        config.put(UploaderHeartbeatApiController.ENABLED_KEY, "false");
        assertEquals(404, send(mvc, heartbeat(UUID.randomUUID().toString(), "")));
        assertEquals(0L, scalar("SELECT count(*) FROM uploader_instance"));
    }

    @Test
    void beyondTheCapANewProgramIsRefusedButKnownOnesKeepReporting() throws Exception {
        String known = null;
        for (int i = 0; i < UploaderHeartbeatApiController.MAX_INSTANCES; i++) {
            String uid = UUID.randomUUID().toString();
            if (known == null) known = uid;
            exec("INSERT INTO uploader_instance (instance_uid, kind, display_name) VALUES ('"
                    + uid + "', 'export-watcher', 'PC-" + i + "')");
        }
        MockMvc mvc = heartbeatMvc();

        assertEquals(429, send(mvc, heartbeat(UUID.randomUUID().toString(), "")));
        assertEquals(200, send(mvc, heartbeat(known, "")));
        assertEquals((long) UploaderHeartbeatApiController.MAX_INSTANCES, scalar("SELECT count(*) FROM uploader_instance"));
    }

    /* ---- the admin list ----------------------------------------------- */

    @Test
    void theListIsSysadminOnly() throws Exception {
        MockMvc mvc = adminMvc(sampler());
        mvc.perform(get("/api/v1/admin/uploaders").session(new MockHttpSession())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/uploaders").session(physician())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/storage").session(physician())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/storage/rescan").session(physician())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/uploaders/1").session(physician())).andExpect(status().isForbidden());
    }

    @Test
    void theListClassifiesFreshSilentAndStoppedPrograms() throws Exception {
        MockMvc hb = heartbeatMvc();
        String fresh = UUID.randomUUID().toString();
        String silent = UUID.randomUUID().toString();
        String stopped = UUID.randomUUID().toString();
        assertEquals(200, send(hb, heartbeat(fresh, ",\"failedFiles\":2,\"problems\":[\"server-unreachable\"]")));
        assertEquals(200, send(hb, heartbeat(silent, "")));
        assertEquals(200, send(hb, heartbeat(stopped, ",\"running\":false,\"stopReason\":\"session-end\"")));
        exec("UPDATE uploader_instance SET last_seen_at = now() - interval '1 hour' WHERE instance_uid = '" + silent + "'");

        JsonNode body = getJson(adminMvc(sampler()), "/api/v1/admin/uploaders");

        assertTrue(body.get("heartbeatEnabled").asBoolean());
        Map<String, JsonNode> byUid = new HashMap<>();
        for (JsonNode u : body.get("uploaders")) {
            // the instance id is the program's credential: never sent back
            assertFalse(u.has("instanceId"));
            assertFalse(u.toString().contains(fresh));
            byUid.put(u.get("id").asText(), u);
        }
        assertEquals(3, byUid.size());
        JsonNode warn = find(body, "warning");
        assertEquals("server-unreachable", warn.get("issues").get(0).asText());
        assertEquals("files-failed", warn.get("issues").get(1).asText());
        assertEquals("CLARUS-PC", warn.get("name").asText());
        assertEquals("export-watcher", warn.get("kind").asText());
        assertTrue(find(body, "offline").get("secondsSinceSeen").asLong() >= 3500);
        assertEquals("session-end", find(body, "stopped").get("stopReason").asText());
    }

    private static JsonNode find(JsonNode body, String status) {
        for (JsonNode u : body.get("uploaders")) {
            if (status.equals(u.get("status").asText())) return u;
        }
        throw new AssertionError("no uploader with status " + status + " in " + body);
    }

    @Test
    void arrivalsAreCountedPerDeviceAndIngress() throws Exception {
        exec("INSERT INTO ingest_item (kind, source_kind, stored_path, status, device, received_at) VALUES "
                + "('dicom', 'it-health', '/x/1', 'UNBOUND', 'clarus', now() - interval '1 hour'), "
                + "('dicom', 'it-health', '/x/2', 'UNBOUND', 'clarus', now() - interval '3 days'), "
                + "('e2e',   'it-health', '/x/3', 'UNBOUND', 'spectralis', now() - interval '10 days')");

        JsonNode body = getJson(adminMvc(sampler()), "/api/v1/admin/uploaders");

        JsonNode clarus = null;
        JsonNode spectralis = null;
        for (JsonNode d : body.get("ingestByDevice")) {
            if (!"it-health".equals(d.get("sourceKind").asText())) continue;
            if ("clarus".equals(d.get("device").asText())) clarus = d;
            if ("spectralis".equals(d.get("device").asText())) spectralis = d;
        }
        assertNotNull(clarus);
        assertEquals(1, clarus.get("last24h").asInt());
        assertEquals(2, clarus.get("last7d").asInt());
        assertNotNull(spectralis);
        assertEquals(0, spectralis.get("last7d").asInt());
        assertNotNull(spectralis.get("lastReceivedAt").asText());
    }

    @Test
    void aProgramCanBeForgotten() throws Exception {
        assertEquals(200, send(heartbeatMvc(), heartbeat(UUID.randomUUID().toString(), "")));
        long id = scalar("SELECT uploader_instance_id FROM uploader_instance");
        MockMvc mvc = adminMvc(sampler());

        mvc.perform(delete("/api/v1/admin/uploaders/" + id).session(sysadmin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/uploaders/" + id).session(sysadmin())).andExpect(status().isNotFound());
        assertEquals(0L, scalar("SELECT count(*) FROM uploader_instance"));
    }

    /* ---- storage ------------------------------------------------------ */

    private void configureStores() throws Exception {
        Path ingest = Files.createDirectories(tmp.resolve("ingest/dicom"));
        Files.write(ingest.resolve("a.dcm"), new byte[1000]);
        Files.write(ingest.resolve("b.dcm"), new byte[500]);
        Path e2e = Files.createDirectories(tmp.resolve("e2e"));
        Files.write(e2e.resolve("scan.e2e"), new byte[2000]);
        Path artifacts = Files.createDirectories(tmp.resolve("artifacts/bscans/x"));
        Files.write(artifacts.resolve("bscan.dcm"), new byte[300]);
        config.put("core.ingest.storePath", tmp.resolve("ingest").toString());
        config.put("core.retinalInference.e2eUploadsPath", e2e.toString());
        config.put("core.retinalInference.artifactStorePath", tmp.resolve("artifacts").toString());
        // configured, but inside the artifacts store: counted there, not twice
        config.put("core.retinalInference.bscanStorePath", tmp.resolve("artifacts/bscans").toString());
        config.put("core.dicom.ingest.storePath", tmp.resolve("never-created").toString());
    }

    @Test
    void aScanMeasuresTheStoresAndThePageReadsIt() throws Exception {
        configureStores();
        StorageUsageSampler sampler = sampler();
        assertTrue(sampler.sampleNow());

        JsonNode body = getJson(adminMvc(sampler), "/api/v1/admin/storage");

        assertNotNull(body.get("sampledAt").asText(null));
        assertFalse(body.get("scanning").asBoolean());
        Map<String, JsonNode> stores = new HashMap<>();
        for (JsonNode s : body.get("stores")) stores.put(s.get("key").asText(), s);
        assertEquals(1500L, stores.get("ingest").get("usedBytes").asLong());
        assertEquals(2L, stores.get("ingest").get("fileCount").asLong());
        assertEquals(2000L, stores.get("e2e-uploads").get("usedBytes").asLong());
        assertEquals(300L, stores.get("retinal-artifacts").get("usedBytes").asLong());
        assertFalse(stores.containsKey("retinal-bscans"));
        assertFalse(stores.get("dicom-ingest").get("present").asBoolean());
        assertTrue(stores.get("ingest").get("complete").asBoolean());
        assertTrue(stores.get("ingest").get("usedBytesBefore").isNull());

        JsonNode fs = body.get("filesystems").get(0);
        assertTrue(fs.get("totalBytes").asLong() > 0);
        assertTrue(fs.get("stores").toString().contains("ingest"));
        assertTrue(fs.get("daysUntilFull").isNull());

        JsonNode db = body.get("database");
        assertTrue(db.get("sizeBytes").asLong() > 0);
        assertTrue(db.get("largestTables").size() > 0);
    }

    @Test
    void aWeekOfShrinkingFreeSpaceBecomesDaysUntilFull() throws Exception {
        configureStores();
        StorageUsageSampler sampler = sampler();
        assertTrue(sampler.sampleNow());
        // The same disk a week ago: 100 GiB more free, and the ingest store smaller.
        exec("INSERT INTO storage_usage_sample (sampled_at, store_key, store_path, present, used_bytes, file_count, "
                + " complete, fs_key, fs_type, fs_total_bytes, fs_usable_bytes) "
                + "SELECT sampled_at - interval '7 days', store_key, store_path, present, 500, 1, complete, fs_key, "
                + " fs_type, fs_total_bytes, fs_usable_bytes + 107374182400 "
                + "FROM storage_usage_sample WHERE store_key = 'ingest'");

        JsonNode body = getJson(adminMvc(sampler), "/api/v1/admin/storage");

        JsonNode ingest = null;
        for (JsonNode s : body.get("stores")) if ("ingest".equals(s.get("key").asText())) ingest = s;
        assertNotNull(ingest);
        assertEquals(500L, ingest.get("usedBytesBefore").asLong());
        JsonNode fs = body.get("filesystems").get(0);
        assertEquals(fs.get("usableBytes").asLong() + 107374182400L, fs.get("usableBytesBefore").asLong());
        double expected = fs.get("usableBytes").asDouble() / (107374182400.0 / 7.0);
        if (expected <= SystemHealthApiController.MAX_DAYS_UNTIL_FULL) {
            assertEquals(Math.floor(expected * 10) / 10.0, fs.get("daysUntilFull").asDouble(), 0.2);
        } else {
            assertTrue(fs.get("daysUntilFull").isNull());
        }
    }

    @Test
    void samplesOlderThanNinetyDaysArePruned() throws Exception {
        exec("INSERT INTO storage_usage_sample (sampled_at, store_key, used_bytes) "
                + "VALUES (now() - interval '100 days', 'ingest', 1)");
        configureStores();

        assertTrue(sampler().sampleNow());

        assertEquals(0L, scalar("SELECT count(*) FROM storage_usage_sample WHERE sampled_at < now() - interval '90 days'"));
        assertEquals(1L, scalar("SELECT count(DISTINCT sampled_at) FROM storage_usage_sample"));
        // the database is sampled with every scan
        assertEquals(1L, scalar("SELECT count(*) FROM storage_usage_sample WHERE store_key = 'database'"));
    }

    @Test
    void aRescanRunsInTheBackgroundAndAnEmptyPageStartsOne() throws Exception {
        configureStores();
        StorageUsageSampler sampler = sampler();
        MockMvc mvc = adminMvc(sampler);

        // Nothing measured yet: the page asks for a scan instead of showing nothing.
        JsonNode first = getJson(mvc, "/api/v1/admin/storage");
        assertTrue(first.get("sampledAt").isNull());
        assertTrue(first.get("scanning").asBoolean());
        waitForScans(sampler, 1);
        assertEquals(1L, scalar("SELECT count(DISTINCT sampled_at) FROM storage_usage_sample"));

        mvc.perform(post("/api/v1/admin/storage/rescan").session(sysadmin())).andExpect(status().isAccepted());
        waitForScans(sampler, 2);
        assertEquals(2L, scalar("SELECT count(DISTINCT sampled_at) FROM storage_usage_sample"));
    }

    /** Until the scan thread has written its rows and let go of the flag (at most 15 s). */
    private static void waitForScans(StorageUsageSampler sampler, long scans) throws Exception {
        for (int i = 0; i < 150; i++) {
            if (!sampler.isScanning()
                    && scalar("SELECT count(DISTINCT sampled_at) FROM storage_usage_sample") >= scans) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the background scan did not finish within 15 s");
    }
}
