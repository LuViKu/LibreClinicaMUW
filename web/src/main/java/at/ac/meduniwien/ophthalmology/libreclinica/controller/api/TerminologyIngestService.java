package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * #26 — Terminology ingest. Streams a FHIR R4 {@code CodeSystem} document
 * (ICD-10-GM: ~17k concepts / 40 MB) and loads it into {@code
 * terminology_concept}, replacing that code-system's rows atomically.
 *
 * <p>Design mirrors the MUW .NET terminology-ingest worker:
 * <ul>
 *   <li><b>Streaming parse</b> — a single forward-only Jackson pass reads the
 *       envelope, then iterates {@code concept[]} one object at a time. The
 *       40 MB-class file is never buffered whole (only the compact CSV is).</li>
 *   <li><b>Binary/text COPY</b> — rows are loaded with {@code COPY … FROM STDIN}
 *       (one PG command), not 17k INSERTs. Beyond raw speed, this sidesteps the
 *       per-statement log4jdbc SQL logging that made a batch-INSERT load
 *       pathologically slow. This is the same choice the .NET worker made
 *       ("binary COPY each row").</li>
 *   <li><b>Atomic swap</b> — the load runs in one transaction: a new
 *       {@code terminology_codesystem_version} row, a per-system delete, then
 *       the COPY. READ COMMITTED readers keep seeing the previous catalogue
 *       until the commit instant, so autocomplete never observes a half-load.</li>
 *   <li><b>Sanity gate</b> — refuses to commit if it parsed fewer than 50 % of
 *       the envelope's declared {@code count}.</li>
 * </ul>
 *
 * <p>Source acquisition (fetching from a pinned upstream URL) is the scheduled
 * worker's job — this service takes an {@link InputStream} so it works the
 * same for a local file (slice 1) and a streamed HTTP body (slice 2).
 */
@Service
public class TerminologyIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(TerminologyIngestService.class);

    private final DataSource dataSource;
    private final JsonFactory jsonFactory = new JsonFactory();

    public TerminologyIngestService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record IngestResult(String codeSystem, int declaredCount, int loaded, String sourceVersion) {}

    /**
     * Ingest a FHIR CodeSystem for {@code codeSystem} (e.g. "icd10gm").
     * @param sourceSha optional upstream fingerprint (pinned commit / file hash), stored for change detection.
     */
    public IngestResult ingestFhirCodeSystem(String codeSystem, String sourceUrl, String sourceSha,
                                             InputStream json) throws IOException, SQLException {
        // Stream-parse into a compact CSV buffer (~2 MB for ICD-10-GM), plus the
        // declared count + envelope version, before touching the table.
        StringBuilder csv = new StringBuilder(4 * 1024 * 1024);
        int[] declared = {0};
        String[] envelopeVersion = {null};
        int loaded;

        try (Connection c = dataSource.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                long versionId = insertVersionRow(c, codeSystem, sourceUrl, sourceSha);
                loaded = streamConceptsToCsv(json, csv, codeSystem, versionId, declared, envelopeVersion);

                if (declared[0] > 0 && loaded < declared[0] / 2) {
                    throw new IOException("Sanity gate: parsed " + loaded + " of declared "
                            + declared[0] + " concepts (<50%) — refusing a likely-truncated ingest");
                }

                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM terminology_concept WHERE code_system = ?")) {
                    del.setString(1, codeSystem);
                    del.executeUpdate();
                }

                if (loaded > 0) {
                    copyCsv(c, "COPY terminology_concept "
                            + "(code_system, code, display, match_text, properties, version_id) "
                            + "FROM STDIN WITH (FORMAT csv)", csv.toString());
                }

                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE terminology_codesystem_version SET concept_count = ?, is_current = TRUE WHERE id = ?")) {
                    upd.setInt(1, loaded);
                    upd.setLong(2, versionId);
                    upd.executeUpdate();
                }
                try (PreparedStatement demote = c.prepareStatement(
                        "UPDATE terminology_codesystem_version SET is_current = FALSE WHERE code_system = ? AND id <> ?")) {
                    demote.setString(1, codeSystem);
                    demote.setLong(2, versionId);
                    demote.executeUpdate();
                }

                c.commit();
                LOG.info("Terminology ingest OK: system={} loaded={} declared={} version={}",
                        codeSystem, loaded, declared[0], envelopeVersion[0]);
                return new IngestResult(codeSystem, declared[0], loaded, envelopeVersion[0]);
            } catch (IOException | SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        }
    }

    /**
     * COPY the CSV into the target via the PG driver's CopyManager, reached by
     * reflection so this module needs no compile-time org.postgresql dependency
     * (the driver is runtime-scoped project-wide; a compile-scope override would
     * bust the Docker build's dependency cache). Running COPY on the unwrapped
     * PG connection also bypasses the log4jdbc per-statement SQL logging that
     * makes a 17k-row INSERT batch pathologically slow.
     */
    private void copyCsv(Connection c, String sql, String csv) throws SQLException {
        try {
            Class<?> baseConnClass = Class.forName("org.postgresql.core.BaseConnection");
            Object pgConn = c.unwrap(baseConnClass);
            Class<?> cmClass = Class.forName("org.postgresql.copy.CopyManager");
            Object cm = cmClass.getConstructor(baseConnClass).newInstance(pgConn);
            cmClass.getMethod("copyIn", String.class, Reader.class).invoke(cm, sql, new StringReader(csv));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SQLException("Terminology COPY failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("PG CopyManager unavailable: " + e.getMessage(), e);
        }
    }

    private long insertVersionRow(Connection c, String codeSystem, String sourceUrl, String sourceSha)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO terminology_codesystem_version (code_system, source_url, source_sha, is_current) "
                        + "VALUES (?, ?, ?, FALSE)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, codeSystem);
            ps.setString(2, sourceUrl);
            ps.setString(3, sourceSha);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Forward-only pass: read the top-level {@code count} + {@code version},
     * then iterate {@code concept[]}, appending one CSV row per concept to
     * {@code csv}. Returns the number of concepts written.
     */
    private int streamConceptsToCsv(InputStream json, StringBuilder csv, String codeSystem, long versionId,
                                    int[] declaredOut, String[] versionOut) throws IOException {
        int loaded = 0;
        try (JsonParser p = jsonFactory.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected a FHIR CodeSystem JSON object at the root");
            }
            while (p.nextToken() != JsonToken.END_OBJECT && !p.isClosed()) {
                String field = p.currentName();
                if (field == null) continue;
                p.nextToken();
                switch (field) {
                    case "count" -> declaredOut[0] = p.getIntValue();
                    case "version" -> versionOut[0] = p.getValueAsString();
                    case "concept" -> loaded = readConceptArray(p, csv, codeSystem, versionId);
                    default -> p.skipChildren();
                }
            }
        }
        return loaded;
    }

    private int readConceptArray(JsonParser p, StringBuilder csv, String codeSystem, long versionId)
            throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) { p.skipChildren(); return 0; }
        int loaded = 0;
        while (p.nextToken() != JsonToken.END_ARRAY) {
            String code = null, display = null, classKind = null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String f = p.currentName();
                p.nextToken();
                if ("code".equals(f)) code = p.getValueAsString();
                else if ("display".equals(f)) display = p.getValueAsString();
                else if ("property".equals(f)) classKind = extractClassKind(p);
                else p.skipChildren();
            }
            if (code == null || code.isBlank() || display == null) continue;
            String properties = classKind == null ? "{}" : "{\"classKind\":\"" + jsonEscape(classKind) + "\"}";
            csv.append(csvField(codeSystem)).append(',')
               .append(csvField(code)).append(',')
               .append(csvField(display)).append(',')
               .append(csvField(normalise(display))).append(',')
               .append(csvField(properties)).append(',')
               .append(versionId).append('\n');
            loaded++;
        }
        return loaded;
    }

    /** Pull the {@code classKind} (chapter | block | category …) from a concept's property[]. */
    private String extractClassKind(JsonParser p) throws IOException {
        String classKind = null;
        if (p.currentToken() != JsonToken.START_ARRAY) { p.skipChildren(); return null; }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            String propCode = null, propVal = null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String f = p.currentName();
                p.nextToken();
                // Property values are polymorphic: valueString/valueCode (scalar)
                // but also valueCoding (an OBJECT). Only scalars can be read as a
                // string; a container value MUST be skipped or the parser desyncs
                // and the concept array terminates early (dropping thousands of
                // rows). classKind is a valueString, so scalar handling suffices.
                if ("code".equals(f)) {
                    propCode = p.getValueAsString();
                } else if (f != null && f.startsWith("value") && p.currentToken().isScalarValue()) {
                    propVal = p.getValueAsString();
                } else {
                    p.skipChildren();
                }
            }
            if ("classKind".equals(propCode) && propVal != null) classKind = propVal;
        }
        return classKind;
    }

    /** Normalised search key: lower-cased, whitespace-collapsed. Umlauts kept (German catalogue). */
    static String normalise(String s) {
        return s.toLowerCase(Locale.GERMAN).trim().replaceAll("\\s+", " ");
    }

    /** CSV field per RFC 4180 / PG COPY CSV: always quoted, embedded quotes doubled. */
    private static String csvField(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
