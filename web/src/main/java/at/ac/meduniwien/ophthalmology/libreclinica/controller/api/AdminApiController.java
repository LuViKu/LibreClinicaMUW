/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.PasswordRequirementsDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeSpec;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeStatus;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.8 legacy-retirement Slice L3 (2026-06-20) — SPA replacement
 * surface for the legacy admin tooling JSPs:
 *
 * <ul>
 *   <li>{@code /pages/SystemStatus} → {@code GET /api/v1/admin/system-status}</li>
 *   <li>{@code /pages/ConfigurePasswordRequirements} →
 *       {@code GET / PUT /api/v1/admin/password-policy}</li>
 *   <li>{@code GET /api/v1/admin/retinal-cluster} — per-node health of
 *       the remote inference cluster + the cron monitor's last alerts
 *       (2026-09-22; no legacy JSP counterpart)</li>
 *   <li>{@code /pages/Configure} → {@code GET /api/v1/admin/config}
 *       (read-only — at MUW these are deployment-time env vars per the
 *       single-site production scope; see project memory)</li>
 * </ul>
 *
 * <p><strong>Scope note.</strong> The legacy {@code ViewSchedulerServlet}
 * is a 2005-era stub whose {@code processRequest} body is empty
 * commented-out code. There is nothing to replicate; an SPA scheduler
 * view is deferred to the Phase E.9 Quartz-integration backlog, not
 * included in L3.
 *
 * <p><strong>Authorization.</strong> All endpoints require a sysadmin
 * session ({@code ub.isSysAdmin()}). Anonymous → 401; non-sysadmin
 * → 403. The PUT path additionally guards the same way; we do NOT rely
 * on the field-validation layer to refuse writes from non-sysadmin
 * sessions.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin tooling",
     description = "Sysadmin-only diagnostic + configuration surfaces — SPA replacement for the legacy admin JSPs.")
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings("null")
public class AdminApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApiController.class);

    private final DataSource dataSource;
    private final DatabaseChangeLogDao databaseChangeLogDao;
    private final ConfigurationDao configurationDao;

    @Autowired
    public AdminApiController(@Qualifier("dataSource") DataSource dataSource,
                              DatabaseChangeLogDao databaseChangeLogDao,
                              ConfigurationDao configurationDao) {
        this.dataSource = dataSource;
        this.databaseChangeLogDao = databaseChangeLogDao;
        this.configurationDao = configurationDao;
    }

    /* ====================================================================== */
    /* System status                                                          */
    /* ====================================================================== */

    @GetMapping(value = "/system-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> systemStatus(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        Map<String, Object> body = new LinkedHashMap<>();

        // JVM facts: pre-derived to plain numbers the SPA can render
        // without re-running the math on every refresh.
        Runtime rt = Runtime.getRuntime();
        long maxBytes = rt.maxMemory();
        long totalBytes = rt.totalMemory();
        long freeBytes = rt.freeMemory();
        long usedBytes = totalBytes - freeBytes;
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("javaVersion", System.getProperty("java.version"));
        jvm.put("vmName", System.getProperty("java.vm.name"));
        jvm.put("heapMaxMb", maxBytes / (1024 * 1024));
        jvm.put("heapUsedMb", usedBytes / (1024 * 1024));
        jvm.put("heapFreeMb", freeBytes / (1024 * 1024));
        jvm.put("threadCount", Thread.activeCount());
        jvm.put("availableProcessors", rt.availableProcessors());
        body.put("jvm", jvm);

        // Database facts: Liquibase changelog count + JDBC reachability.
        // The reachability probe runs in-band so this endpoint is also
        // useful as a quick "is the pool wedged" smoke check.
        Map<String, Object> database = new LinkedHashMap<>();
        try {
            database.put("liquibaseChangelogCount", databaseChangeLogDao.count());
        } catch (RuntimeException e) {
            database.put("liquibaseChangelogCount", null);
            database.put("liquibaseError", e.getMessage());
        }
        try (Connection c = dataSource.getConnection()) {
            database.put("reachable", c != null && c.isValid(2));
            database.put("databaseProductName", c.getMetaData().getDatabaseProductName());
            database.put("databaseProductVersion", c.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            database.put("reachable", false);
            database.put("connectError", e.getMessage());
        }
        body.put("database", database);

        // Application facts: OOM marker (the legacy SystemStatusServlet
        // surfaced this; some watchdog code still sets the session
        // attribute). The marker is best-effort — its absence is normal.
        Map<String, Object> application = new LinkedHashMap<>();
        boolean ome = session.getAttribute("ome") != null;
        application.put("status", ome ? "OutOfMemory" : "OK");
        application.put("upSinceMillis", upSinceMillis());
        body.put("application", application);

        return ResponseEntity.ok(body);
    }

    /* ====================================================================== */
    /* Password policy                                                        */
    /* ====================================================================== */

    @GetMapping(value = "/password-policy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPasswordPolicy(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        PasswordRequirementsDao dao = newPasswordDao();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requireLower", dao.hasLower());
        body.put("requireUpper", dao.hasUpper());
        body.put("requireDigits", dao.hasDigits());
        body.put("requireSpecials", dao.hasSpecials());
        body.put("minLength", dao.minLength());
        body.put("maxLength", dao.maxLength());
        body.put("expirationDays", dao.expirationDays());
        body.put("changeRequiredOnFirstLogin", dao.changeRequired());
        // Pass through the static specials alphabet so the SPA can
        // surface "Allowed special characters: !@#$%&*()" in copy
        // without rebaking the same constant in TypeScript.
        body.put("specialsAlphabet", PasswordRequirementsDao.SPECIALS);
        return ResponseEntity.ok(body);
    }

    public record PasswordPolicyUpdate(
            Boolean requireLower,
            Boolean requireUpper,
            Boolean requireDigits,
            Boolean requireSpecials,
            Integer minLength,
            Integer maxLength,
            Integer expirationDays,
            Boolean changeRequiredOnFirstLogin) {}

    @PutMapping(value = "/password-policy",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putPasswordPolicy(@RequestBody(required = false) PasswordPolicyUpdate body,
                                               HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required.", List.of()));
        }

        List<FieldError> errors = new ArrayList<>();
        validateRange(errors, "minLength", body.minLength(), 1, 256);
        validateRange(errors, "maxLength", body.maxLength(), 1, 256);
        if (body.minLength() != null && body.maxLength() != null
                && body.minLength() > body.maxLength()) {
            errors.add(new FieldError("minLength",
                    "Minimum length must not exceed maximum length."));
        }
        // PWD_EXPIRATION_DAYS = 0 means "never expires". Allow that.
        validateRange(errors, "expirationDays", body.expirationDays(), 0, 3650);

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed.", errors));
        }

        PasswordRequirementsDao dao = newPasswordDao();
        if (body.requireLower()    != null) dao.setHasLower(body.requireLower());
        if (body.requireUpper()    != null) dao.setHasUpper(body.requireUpper());
        if (body.requireDigits()   != null) dao.setHasDigits(body.requireDigits());
        if (body.requireSpecials() != null) dao.setHasSpecials(body.requireSpecials());
        if (body.minLength()       != null) dao.setMinLength(body.minLength());
        if (body.maxLength()       != null) dao.setMaxLength(body.maxLength());
        if (body.expirationDays()  != null) dao.setExpirationDays(body.expirationDays());
        if (body.changeRequiredOnFirstLogin() != null) {
            // PWD_CHANGE_REQUIRED is persisted as an int despite the
            // boolean semantics. Keep the bool → int translation in one
            // place so the SPA never has to care.
            dao.setChangeRequired(body.changeRequiredOnFirstLogin() ? 1 : 0);
        }

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        LOG.info("Password policy updated by sysadmin id={} name={}",
                ub == null ? null : ub.getId(),
                ub == null ? null : ub.getName());

        return getPasswordPolicy(session);
    }

    /* ====================================================================== */
    /* Config                                                                 */
    /* ====================================================================== */

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getConfig(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultTimezone", TimeZone.getDefault().getID());
        body.put("userLanguage", System.getProperty("user.language"));
        body.put("userCountry", System.getProperty("user.country"));
        body.put("fileEncoding", System.getProperty("file.encoding"));
        body.put("osName", System.getProperty("os.name"));
        body.put("osArch", System.getProperty("os.arch"));
        // Surface a handful of MUW-relevant ENV reads; null when unset
        // is itself useful diagnostic information.
        body.put("javaOpts", System.getenv("JAVA_OPTS"));
        body.put("retinalInferenceRemotePushUrl",
                System.getenv("CORE_RETINAL_INFERENCE_REMOTE_PUSH_URL"));
        body.put("ssoEnabled", "true".equalsIgnoreCase(System.getenv("SSO_ENABLED")));
        body.put("readOnly", true);
        return ResponseEntity.ok(body);
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /**
     * Returns null on permitted, ResponseEntity (401/403) on denied.
     * Inlined as a method so each handler stays a 5-line dispatch.
     */
    /* ====================================================================== */
    /* Retinal inference cluster (2026-09-22)                                 */
    /* ====================================================================== */

    /**
     * Per-node probe timeout. Short on purpose: a hung node must cost this
     * page three seconds, not the sixty-minute {@code remotePushTimeoutSecs}
     * the job path allows.
     */
    private static final Duration CLUSTER_PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final int MONITOR_TAIL_LINES = 20;

    /**
     * {@code GET /api/v1/admin/retinal-cluster} — per-node health of the
     * remote retinal-inference cluster, plus the tail of the app-VM cron
     * monitor's log.
     *
     * <p>Why this exists: during the 2026-09 outage the cluster was dead for
     * nineteen days and nothing inside the app said so — the only symptom was
     * jobs failing with "Remote /run returned null". This is the pull-side
     * view of that: <em>is each node listening, healthy, missing tasks, and
     * which host and GPU is it on</em>, answered in one screen instead of an
     * afternoon of SSH.
     *
     * <p>Kept separate from {@code /system-status} so the JVM/DB panels
     * render immediately even while a dead node runs out its probe timeout.
     *
     * <p>Probes {@code core.retinalInference.clusterNodes} (the real nodes),
     * not {@code remotePushUrl}: behind the nginx failover the push URL is
     * healthy as long as <em>either</em> node is, which would read as green
     * while the primary is dead. Falls back to the push URL as a single row
     * when the node list is unset.
     */
    @GetMapping(value = "/retinal-cluster", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retinalCluster(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        String remoteUrl = configField("core.retinalInference.remotePushUrl", "");
        String nodesRaw = configField("core.retinalInference.clusterNodes", "");
        List<NodeSpec> specs = RetinalClusterHealth.parseNodes(nodesRaw, remoteUrl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", !specs.isEmpty());
        body.put("remotePushUrl", remoteUrl);
        body.put("expectedTasks", RetinalClusterHealth.EXPECTED_TASKS);
        body.put("nodes", probeCluster(specs));

        String logPath = configField("core.retinalInference.clusterMonitorLog", "");
        if (!logPath.isBlank()) {
            Path log = Path.of(logPath);
            boolean readable = Files.isReadable(log);
            Map<String, Object> monitor = new LinkedHashMap<>();
            monitor.put("path", logPath);
            monitor.put("readable", readable);
            monitor.put("lines", readable
                    ? RetinalClusterHealth.tailLines(log, MONITOR_TAIL_LINES)
                    : List.of());
            body.put("monitor", monitor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * The network step, isolated so tests can substitute canned results and
     * never open a socket. Production probes every node concurrently.
     */
    protected List<NodeStatus> probeCluster(List<NodeSpec> specs) {
        return new RetinalClusterHealth(CLUSTER_PROBE_TIMEOUT).probeAll(specs);
    }

    /**
     * {@code datainfo.properties} read. Overridable because
     * {@link CoreResources} is not initialised in MockMvc runs, where the
     * static call throws and this would otherwise always return the fallback.
     */
    protected String configField(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable outside a booted container.
        }
        return fallback;
    }

    private ResponseEntity<?> requireSysadmin(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Sysadmin privilege required."));
        }
        return null;
    }

    /**
     * Build the {@link PasswordRequirementsDao} façade on demand.
     * Made overridable so tests can swap in a recording fake without
     * standing up the {@link ConfigurationDao} hibernate session.
     */
    protected PasswordRequirementsDao newPasswordDao() {
        return new PasswordRequirementsDao(configurationDao);
    }

    private static void validateRange(List<FieldError> errors, String name, Integer value,
                                      int lo, int hi) {
        if (value == null) return;
        if (value < lo || value > hi) {
            errors.add(new FieldError(name,
                    name + " must be between " + lo + " and " + hi + "."));
        }
    }

    /**
     * Best-effort process-uptime read from the JVM management bean.
     * Returns 0 when the bean isn't available (e.g. some hardened
     * security managers). Plain millis since boot; the SPA renders.
     */
    private static long upSinceMillis() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
