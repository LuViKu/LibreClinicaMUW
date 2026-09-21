/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.study;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * P3.5 — what a study does, answered per study.
 *
 * <p>Whether a study receives DICOM, accepts OCT uploads, runs inference,
 * offers the today's-visits list: all of that was instance-wide properties in
 * {@code datainfo.properties}, some of them lists of study OIDs that an
 * administrator could only change by editing a file on the server and
 * restarting it. On an instance running three studies that is awkward, and the
 * default of an unset key — "every study" — is a disclosure.
 *
 * <p><strong>Four places are consulted, in order.</strong>
 *
 * <ol>
 *   <li>The site's own row. A site that differs from its study says so.</li>
 *   <li>The parent study's row. The normal place an answer lives; a site
 *       inherits rather than restating.</li>
 *   <li>The {@code core.*} property the setting replaces. This is what makes
 *       the change invisible on deployment: an instance that has configured
 *       the old key keeps behaving exactly as it did.</li>
 *   <li>The code default.</li>
 * </ol>
 *
 * <p>So an absent row means "as before", and nothing changes until somebody
 * sets something. That matters most for {@code ai.arm.*}: silently changing
 * which group is blinded would be the worst possible behaviour to alter by
 * accident.
 */
public class StudySettingService {

    private static final Logger LOG = LoggerFactory.getLogger(StudySettingService.class);

    /* ------------------------------------------------------------------ */
    /* The keys                                                            */
    /* ------------------------------------------------------------------ */

    /** Whether the Modality Worklist offers this study's visits to a camera. */
    public static final String INGEST_DICOM_ENABLED = "ingest.dicom.enabled";

    /** Whether the public OCT portal accepts uploads for this study. */
    public static final String INGEST_OCT_ENABLED = "ingest.oct.enabled";

    /** Whether the public image portal accepts uploads for this study. */
    public static final String INGEST_IMAGE_ENABLED = "ingest.image.enabled";

    /** Whether filing an .e2e enqueues inference work. */
    public static final String INFERENCE_ENABLED = "inference.enabled";

    /** The subject-group names this study randomises AI visibility on. */
    public static final String AI_ARM_SHOWN_GROUP = "ai.arm.shownGroup";
    public static final String AI_ARM_HIDDEN_GROUP = "ai.arm.hiddenGroup";

    /** Whether a subject's files may be exported as a multimodal bundle. */
    public static final String EXPORT_BUNDLE_ENABLED = "export.bundle.enabled";

    /**
     * Whether the unauthenticated upload page lists the day's visits.
     *
     * <p>A list of today's patients on a page that needs no login is a
     * disclosure in its own right, which is why it is off unless a study says
     * otherwise — see the data-protection checkpoint in the plan.
     */
    public static final String PORTAL_TODAYS_VISITS = "portal.todaysVisits";

    /**
     * The keys this release understands. An unknown key is refused on write,
     * and this is the list {@code /me} and the admin panel enumerate — one
     * list, so a key added here reaches both without being added twice.
     */
    public static final List<String> KNOWN_KEYS = List.of(
            INGEST_DICOM_ENABLED,
            INGEST_OCT_ENABLED,
            INGEST_IMAGE_ENABLED,
            INFERENCE_ENABLED,
            AI_ARM_SHOWN_GROUP,
            AI_ARM_HIDDEN_GROUP,
            EXPORT_BUNDLE_ENABLED,
            PORTAL_TODAYS_VISITS);

    /**
     * The {@code core.*} property each key falls back to, and the code default
     * behind that.
     *
     * <p>The ingest keys fall back to the P1-9 study-OID lists rather than to a
     * boolean, so this is handled by {@link #legacyScopeAllows} instead — a
     * list naming the study means enabled, and a blank list means every study,
     * which is what those keys have always meant.
     */
    private static final Map<String, String[]> FALLBACKS = new LinkedHashMap<>();

    static {
        // key -> { core.* property, code default }
        FALLBACKS.put(INFERENCE_ENABLED, new String[] { "core.retinalInference.enabled", "true" });
        FALLBACKS.put(EXPORT_BUNDLE_ENABLED, new String[] { "core.export.bundle.enabled", "false" });
        FALLBACKS.put(PORTAL_TODAYS_VISITS, new String[] { "core.ingest.portal.todaysVisits", "false" });
    }

    private final DataSource dataSource;

    public StudySettingService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The value for a study, or null when nothing anywhere answers.
     *
     * @param studyId the site or the study; a site's own row wins, then its
     *                parent's
     */
    public String resolve(int studyId, String key) {
        String own = stored(studyId, key);
        if (own != null) return own;
        String configured = cfg(key);
        return configured != null ? configured : codeDefault(key);
    }

    /** As {@link #resolve}, read as a boolean. */
    public boolean isEnabled(int studyId, String key) {
        return Boolean.parseBoolean(resolve(studyId, key));
    }

    /**
     * Whether an ingress is open for a study.
     *
     * <p>Falls back to the P1-9 study-OID list rather than to a boolean: a
     * deployment that configured {@code core.dicom.worklist.studyOids} keeps
     * exactly the scope it set, and a blank list keeps meaning "every study".
     * That is what lets this ship without a configuration change.
     */
    public boolean ingressEnabled(int studyId, String key, String legacyScopeProperty) {
        String own = stored(studyId, key);
        if (own != null) return Boolean.parseBoolean(own);
        return legacyScopeAllows(studyId, legacyScopeProperty);
    }

    /**
     * What the platform will actually do for this study, key by key.
     *
     * <p>Distinct from {@link #allFor}: that returns what a study has
     * <em>set</em>, which is what an administrator needs to see before
     * changing something. This returns what is in force after the site →
     * parent → configuration → code-default fallback, which is what a client
     * deciding whether to offer a feature needs. Confusing the two would make
     * an untouched study look as though every feature were off.
     */
    public Map<String, String> resolvedFor(int studyId) {
        // One pass over the study's rows rather than a lookup per key: this is
        // on the /me path, which every app boot and every study switch calls,
        // and eight round trips to answer eight booleans is a cost paid on
        // every page load.
        Map<String, String> set = allFor(studyId);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            String own = set.get(key);
            if (own != null) {
                out.put(key, own);
                continue;
            }
            String configured = cfg(key);
            out.put(key, configured != null ? configured : codeDefault(key));
        }
        return out;
    }

    /** Everything set for a study, for the admin panel and {@code /me}. */
    public Map<String, String> allFor(int studyId) {
        Map<String, String> out = new LinkedHashMap<>();
        // Parent first, so a site's own row overwrites it.
        Integer parent = parentOf(studyId);
        if (parent != null) out.putAll(rowsFor(parent));
        out.putAll(rowsFor(studyId));
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Writing                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Set or clear one setting.
     *
     * @param value null clears the row, which restores "as before" rather than
     *              storing a value that happens to match the default — the two
     *              differ the moment the default changes
     */
    public void put(int studyId, String key, String value, int actorUserId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM study_setting WHERE study_id = ? AND setting_key = ?")) {
                    ps.setInt(1, studyId);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_setting (study_id, setting_key, value, updated_by_user_id) "
                            + "VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (study_id, setting_key) DO UPDATE "
                            + "   SET value = EXCLUDED.value, date_updated = NOW(), "
                            + "       updated_by_user_id = EXCLUDED.updated_by_user_id")) {
                ps.setInt(1, studyId);
                ps.setString(2, key);
                ps.setString(3, value);
                ps.setInt(4, actorUserId);
                ps.executeUpdate();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* internals                                                           */
    /* ------------------------------------------------------------------ */

    /** This study's row, or its parent's. */
    private String stored(int studyId, String key) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT s.value "
                             + "  FROM study_setting s "
                             + " WHERE s.setting_key = ? "
                             + "   AND s.study_id IN (?, COALESCE((SELECT parent_study_id FROM study "
                             + "                                    WHERE study_id = ?), -1)) "
                             // The site's own row first; the parent is the fallback.
                             + " ORDER BY CASE WHEN s.study_id = ? THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, key);
            ps.setInt(2, studyId);
            ps.setInt(3, studyId);
            ps.setInt(4, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("study_setting lookup failed for {}/{}: {}", studyId, key, e.getMessage());
            return null;
        }
    }

    private Map<String, String> rowsFor(int studyId) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT setting_key, value FROM study_setting WHERE study_id = ? "
                             + "ORDER BY setting_key")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            LOG.warn("study_setting listing failed for {}: {}", studyId, e.getMessage());
        }
        return out;
    }

    private Integer parentOf(int studyId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT parent_study_id FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int p = rs.getInt(1);
                return rs.wasNull() || p == 0 ? null : p;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * The P1-9 behaviour, unchanged: a comma-separated list of study OIDs, and
     * blank means every study.
     */
    private boolean legacyScopeAllows(int studyId, String property) {
        String raw = property == null ? null : cfgRaw(property);
        if (raw == null || raw.isBlank()) return true;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oc_oid FROM study "
                             + " WHERE study_id = ? "
                             + "    OR study_id = (SELECT parent_study_id FROM study WHERE study_id = ?)")) {
            ps.setInt(1, studyId);
            ps.setInt(2, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String oid = rs.getString(1);
                    for (String named : raw.split(",")) {
                        if (named.trim().equalsIgnoreCase(oid)) return true;
                    }
                }
            }
        } catch (SQLException e) {
            // Fail closed on a surface that hands data to a device.
            LOG.warn("legacy study-scope check failed for {}: {}", studyId, e.getMessage());
            return false;
        }
        return false;
    }

    private static String cfg(String key) {
        String[] fallback = FALLBACKS.get(key);
        if (fallback == null) return null;
        String raw = cfgRaw(fallback[0]);
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static String codeDefault(String key) {
        String[] fallback = FALLBACKS.get(key);
        return fallback == null ? null : fallback[1];
    }

    private static String cfgRaw(String property) {
        try {
            String raw = CoreResources.getField(property);
            return raw == null || raw.isBlank() ? null : raw.trim();
        } catch (Exception noContext) {
            // CoreResources is not initialised in some test paths.
            return null;
        }
    }
}
