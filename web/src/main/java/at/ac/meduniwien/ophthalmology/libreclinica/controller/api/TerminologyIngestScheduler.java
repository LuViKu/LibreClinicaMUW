package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

/**
 * #26 Slice 2 — scheduled medication-catalogue ingest.
 *
 * <p>Off-peak, mirrors the MUW .NET terminology-ingest worker: fetch the
 * pinned upstream FHIR {@code CodeSystem}, detect change against the stored
 * fingerprint, and only re-load when it moved. Source is the ELGA termgit
 * ASP-Liste (Austrian medicinal products) on GitLab.
 *
 * <p><b>Change detection</b> uses GitLab's file-metadata endpoint
 * ({@code …/repository/files/{path}?ref=}), which returns {@code content_sha256}
 * WITHOUT the (95 MB) body. Only when that fingerprint differs from the current
 * {@code terminology_codesystem_version.source_sha} does the worker stream the
 * {@code /raw} body into {@link TerminologyIngestService} — so a quiet night is
 * one cheap metadata request, not a 95 MB download.
 *
 * <p><b>Disabled by default</b> ({@code core.terminology.medication.enabled}):
 * dev / CI must not reach out to the internet or load 21k rows on every boot.
 * The single-host dev compose keeps working untouched with the flag unset.
 */
@Component
public class TerminologyIngestScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TerminologyIngestScheduler.class);
    private static final String SYSTEM = "medication";

    // Defaults target the ELGA termgit ASP-Liste. All overridable via datainfo.
    private static final String DEFAULT_PROJECT = "elga-gmbh%2Ftermgit";
    private static final String DEFAULT_PATH =
            "terminologies%2FCodeSystem-asp-liste%2FCodeSystem-asp-liste.4.fhir.json";
    private static final String DEFAULT_REF = "main";

    private final TerminologyIngestService ingestService;
    private final DataSource dataSource;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public TerminologyIngestScheduler(TerminologyIngestService ingestService,
                                      @Qualifier("dataSource") DataSource dataSource) {
        this.ingestService = ingestService;
        this.dataSource = dataSource;
    }

    /**
     * Nightly at 03:30 Europe/Vienna (off-peak). No-ops unless explicitly
     * enabled; the actual work + change-detection happens in {@link #runIngest}.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Vienna")
    public void scheduledIngest() {
        if (!enabled()) {
            LOG.debug("Medication ingest disabled (core.terminology.medication.enabled != true) — skipping");
            return;
        }
        try {
            runIngest();
        } catch (Exception e) {
            // A scheduled job must never propagate — log and let the next run retry.
            LOG.warn("Scheduled medication ingest failed: {}", e.toString());
        }
    }

    /** Fetch metadata, compare fingerprints, and ingest only when changed. */
    public void runIngest() throws Exception {
        String project = cfg("core.terminology.medication.gitlabProject", DEFAULT_PROJECT);
        String path = cfg("core.terminology.medication.filePath", DEFAULT_PATH);
        String ref = cfg("core.terminology.medication.ref", DEFAULT_REF);
        String base = "https://gitlab.com/api/v4/projects/" + project + "/repository/files/" + path;
        String metaUrl = base + "?ref=" + ref;
        String rawUrl = base + "/raw?ref=" + ref;

        String upstreamSha = fetchContentSha256(metaUrl);
        if (upstreamSha == null) {
            LOG.warn("Medication ingest: could not read content_sha256 from {} — skipping", metaUrl);
            return;
        }
        String currentSha = currentSourceSha();
        if (upstreamSha.equals(currentSha)) {
            LOG.info("Medication ingest: upstream unchanged (sha={}) — nothing to do", shortSha(upstreamSha));
            return;
        }
        LOG.info("Medication ingest: upstream changed (was={} now={}) — loading {}",
                shortSha(currentSha), shortSha(upstreamSha), rawUrl);

        HttpRequest rawReq = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(rawReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            LOG.warn("Medication ingest: raw fetch HTTP {} — skipping", resp.statusCode());
            resp.body().close();
            return;
        }
        try (InputStream body = resp.body()) {
            TerminologyIngestService.IngestResult r =
                    ingestService.ingestFhirCodeSystem(SYSTEM, rawUrl, upstreamSha, body);
            LOG.info("Medication ingest OK: loaded={} declared={} version={}",
                    r.loaded(), r.declaredCount(), r.sourceVersion());
        }
    }

    /** GET the GitLab file-metadata JSON and pull {@code content_sha256}. */
    private String fetchContentSha256(String metaUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(metaUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            LOG.warn("Medication ingest: metadata HTTP {} for {}", resp.statusCode(), metaUrl);
            resp.body().close();
            return null;
        }
        try (InputStream in = resp.body(); JsonParser p = jsonFactory.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return null;
            while (p.nextToken() != JsonToken.END_OBJECT && !p.isClosed()) {
                String field = p.currentName();
                p.nextToken();
                if ("content_sha256".equals(field)) {
                    return p.getValueAsString();
                }
                p.skipChildren();
            }
        }
        return null;
    }

    private String currentSourceSha() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT source_sha FROM terminology_codesystem_version "
                             + "WHERE code_system = ? AND is_current = TRUE")) {
            ps.setString(1, SYSTEM);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            LOG.warn("Medication ingest: could not read current source_sha: {}", e.toString());
            return null;
        }
    }

    private static boolean enabled() {
        return "true".equalsIgnoreCase(cfg("core.terminology.medication.enabled", "false"));
    }

    private static String cfg(String key, String def) {
        try {
            String v = CoreResources.getField(key);
            return (v == null || v.isBlank()) ? def : v.trim();
        } catch (Exception e) {
            return def;
        }
    }

    private static String shortSha(String sha) {
        if (sha == null) return "none";
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }
}
