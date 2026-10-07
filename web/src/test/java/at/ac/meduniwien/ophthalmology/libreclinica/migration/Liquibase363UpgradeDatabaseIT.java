/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import at.ac.meduniwien.ophthalmology.libreclinica.config.LaxParsingSpringLiquibase;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import liquibase.integration.spring.SpringLiquibase;

/**
 * The production upgrade path: a database whose {@code databasechangelog} was
 * written by Liquibase 3.6.3 starts on the Liquibase this build ships.
 * <p>
 * Every other Liquibase run in the test suite builds an empty database and then
 * validates the checksums the same version just wrote, so a Liquibase release
 * that computes a heritage changeset's checksum differently passes all of them.
 * 4.33.0 is one: it adds {@code rawDateValue} to the checksum of every changeset
 * that uses {@code valueDate}, and fails startup on every existing database.
 * <p>
 * This test puts back the {@code 8:} checksums a 3.6.3 build stored, from a frozen
 * fixture, and runs the changelog again. Liquibase must accept every one of them,
 * run nothing (a {@code runOnChange} changeset whose checksum no longer matches is
 * re-run, not rejected) and rewrite them as {@code 9:}.
 */
class Liquibase363UpgradeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String FIXTURE = "databasechangelog-liquibase-3.6.3.tsv";

    @Test
    void aDatabaseLiquibase363BuiltUpgradesWithoutRunningAnything() throws Exception {
        List<String[]> fixture = readFixture();
        assertTrue(fixture.size() > 1000, "the fixture should hold the whole 3.6.3 changelog");

        List<String> unmatched = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE databasechangelog SET md5sum = ?"
                     + " WHERE id = ? AND author = ? AND filename = ?")) {
            for (String[] row : fixture) {
                ps.setString(1, row[3]);
                ps.setString(2, row[0]);
                ps.setString(3, row[1]);
                ps.setString(4, row[2]);
                if (ps.executeUpdate() != 1) {
                    unmatched.add(row[2] + "::" + row[0] + "::" + row[1]);
                }
            }
        }
        assertTrue(unmatched.isEmpty(), "changesets in the 3.6.3 fixture that this changelog no longer"
                + " records (an existing changeset was removed or moved): " + unmatched);
        assertEquals(fixture.size(), countOf("SELECT count(*) FROM databasechangelog WHERE md5sum LIKE '8:%'"));

        String before = changelogWithoutChecksums();

        SpringLiquibase upgrade = new LaxParsingSpringLiquibase();
        upgrade.setDataSource(DATA_SOURCE);
        upgrade.setChangeLog("classpath:migration/master.xml");
        upgrade.setResourceLoader(new DefaultResourceLoader());
        upgrade.afterPropertiesSet();

        assertEquals(before, changelogWithoutChecksums(),
                "starting on a 3.6.3-built database ran or re-ran changesets");
        assertEquals(0, countOf("SELECT count(*) FROM databasechangelog WHERE md5sum NOT LIKE '9:%'"),
                "every 3.6.3 checksum should have been validated and rewritten as 9:");
    }

    /* ---------------- helpers ---------------- */

    /** Every column but md5sum, row by row: what a changeset that runs again would change. */
    private String changelogWithoutChecksums() throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DATA_SOURCE.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM databasechangelog ORDER BY orderexecuted, id")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (!"md5sum".equalsIgnoreCase(md.getColumnName(i))) {
                        out.append(rs.getString(i)).append('|');
                    }
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private int countOf(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String[]> readFixture() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(fixturePath(), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cols = line.split("\t", -1);
            if (cols.length != 4) {
                throw new IllegalStateException("malformed fixture line: " + line);
            }
            rows.add(cols);
        }
        return rows;
    }

    /**
     * Read from the source tree: the build copies only .properties and .xml
     * test resources. Resolves whether surefire runs with basedir {@code web/}
     * or the repo root.
     */
    private static Path fixturePath() {
        for (Path p : List.of(Path.of("src/test/data", FIXTURE), Path.of("web/src/test/data", FIXTURE))) {
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("cannot locate " + FIXTURE + " from " + Path.of("").toAbsolutePath());
    }
}
