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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Which studies a device-facing or unauthenticated surface may see.
 *
 * <p>The DR-025 Modality Worklist hands a camera the subject labels and
 * demographics of every visit scheduled in the date window — across every
 * study on the instance. A handheld fundus camera on a clinic bench is a
 * shared device, and a camera enrolled for one study has no business
 * displaying another study's schedule; the same applies to the label lookups
 * on the unauthenticated upload pages.
 *
 * <p>Two configuration keys name the studies each surface may reach, as a
 * comma-separated list of study OIDs:
 *
 * <ul>
 *   <li>{@code core.dicom.worklist.studyOids} — what the worklist offers</li>
 *   <li>{@code core.ingest.portal.studyOids} — what the upload pages resolve</li>
 * </ul>
 *
 * <p>Blank means "every study", which is the pre-existing behaviour and the
 * sensible default for a single-study dev instance. Production sets them: on a
 * multi-study instance an unset key is a disclosure, so the resolved scope is
 * logged at startup of each request path that uses it.
 *
 * <p>Sites are included with their parent: naming a parent study OID covers
 * every site beneath it, since a visit belongs to the site but the study is
 * what the operator enrolled.
 *
 * <p><strong>P3.5 — a study's own switch wins.</strong> Whether a study
 * receives DICOM or accepts uploads is now {@code study_setting}, which an
 * administrator changes without editing a file on the server. The
 * configuration keys remain as the answer for studies that have set nothing,
 * so a deployment that configured them keeps exactly the scope it chose, and
 * an instance that never touched them keeps the "every study" default. They
 * go away once every deployment has moved.
 */
final class StudyScopeConfig {

    private static final Logger LOG = LoggerFactory.getLogger(StudyScopeConfig.class);

    static final String WORKLIST_KEY = "core.dicom.worklist.studyOids";
    static final String PORTAL_KEY = "core.ingest.portal.studyOids";

    private StudyScopeConfig() {}

    /**
     * Resolve a key's study OIDs to study ids, including child sites.
     *
     * @return {@code null} when the key is unset or blank — meaning "do not
     *         restrict"; otherwise the set of study ids in scope (possibly
     *         empty when every configured OID is unknown, which restricts to
     *         nothing rather than silently widening).
     */
    static Set<Integer> studyIdsFor(DataSource dataSource, String configKey) {
        Set<Integer> byStudySetting = studyIdsFromSetting(dataSource, settingKeyFor(configKey));
        if (byStudySetting != null) return byStudySetting;

        String raw = cfg(configKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> oids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String oid = part.trim();
            if (!oid.isEmpty()) oids.add(oid);
        }
        if (oids.isEmpty()) return null;

        Set<Integer> ids = new LinkedHashSet<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(oids.size(), "?"));
        String sql = "SELECT s.study_id FROM study s "
                + " WHERE s.oc_oid IN (" + placeholders + ") "
                + "    OR s.parent_study_id IN (SELECT p.study_id FROM study p "
                + "                              WHERE p.oc_oid IN (" + placeholders + "))";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String oid : oids) ps.setString(i++, oid);
            for (String oid : oids) ps.setString(i++, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            // Restricting to nothing is the safe failure: the alternative
            // hands a device every study's schedule.
            LOG.error("could not resolve {} — restricting to no studies: {}", configKey, e.getMessage());
            return Set.of();
        }
        if (ids.isEmpty()) {
            LOG.warn("{} names no study that exists — restricting to no studies", configKey);
        }
        return ids;
    }

    /** The per-study switch each legacy scope key corresponds to. */
    private static String settingKeyFor(String configKey) {
        if (WORKLIST_KEY.equals(configKey)) return "ingest.dicom.enabled";
        if (PORTAL_KEY.equals(configKey)) return "ingest.image.enabled";
        return null;
    }

    /**
     * The studies that have turned this surface on, or null when none has said
     * anything and the configuration key should answer instead.
     *
     * <p>Sites are covered by their parent's answer unless they override it,
     * which is the same inheritance every other setting uses.
     *
     * <p>A study that explicitly turns a surface OFF is excluded even when its
     * parent is on — the {@code NOT EXISTS} clause is what makes an override
     * an override rather than an addition.
     */
    private static Set<Integer> studyIdsFromSetting(DataSource dataSource, String settingKey) {
        if (settingKey == null) return null;
        Set<Integer> ids = new LinkedHashSet<>();
        boolean anySet = false;
        String sql = "SELECT s.study_id, "
                + "       COALESCE(own.value, parent.value) AS effective "
                + "  FROM study s "
                + "  LEFT JOIN study_setting own "
                + "    ON own.study_id = s.study_id AND own.setting_key = ? "
                + "  LEFT JOIN study_setting parent "
                + "    ON parent.study_id = s.parent_study_id AND parent.setting_key = ? "
                + " WHERE own.value IS NOT NULL OR parent.value IS NOT NULL";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, settingKey);
            ps.setString(2, settingKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anySet = true;
                    if (Boolean.parseBoolean(rs.getString(2))) ids.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            // Restricting to nothing is the safe failure: the alternative
            // hands a device every study's schedule.
            LOG.error("could not resolve {} — restricting to no studies: {}", settingKey, e.getMessage());
            return Set.of();
        }
        if (!anySet) return null;
        if (ids.isEmpty()) {
            LOG.warn("{} is set but no study has it enabled — restricting to no studies", settingKey);
        }
        return ids;
    }

    /** Renders a resolved scope as a SQL IN-list of integers, or null when unrestricted. */
    static String inClauseOrNull(Set<Integer> studyIds) {
        if (studyIds == null) return null;
        if (studyIds.isEmpty()) return "(-1)";
        StringBuilder sb = new StringBuilder("(");
        for (Integer id : studyIds) {
            if (sb.length() > 1) sb.append(',');
            sb.append(id.intValue());
        }
        return sb.append(')').toString();
    }

    private static String cfg(String key) {
        try {
            String raw = CoreResources.getField(key);
            return raw == null ? null : raw.trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
