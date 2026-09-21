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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import liquibase.integration.spring.SpringLiquibase;

/**
 * Migration safety net for the pilot-readiness work.
 *
 * <p>Every deployment applies {@code migration/master.xml} to a database that
 * already carries clinical data, and the runbook's dry-run step asserts that a
 * release adds exactly the expected number of changesets. Two invariants make
 * that trustworthy, and both are cheap to check:
 *
 * <ol>
 *   <li><strong>Idempotency</strong> — a second run of the same changelog must
 *       apply nothing. A changeset that re-runs (a missing precondition, a
 *       mutated checksum) shows up here rather than on the production host.</li>
 *   <li><strong>Wiring + rollback</strong> — an institutional changelog file
 *       that is not included from {@code master.xml} is dead code that silently
 *       never runs, and (from 2026-09-18 on) new files carry a
 *       {@code &lt;rollback&gt;} so the staging-clone dry run can be rewound.</li>
 * </ol>
 */
class LiquibaseChangelogIdempotencyIT extends AbstractApiControllerDatabaseIT {

    /**
     * Files older than this pre-date the convention; 36 of them have no
     * explicit rollback and are deliberately grandfathered rather than
     * rewritten (editing a deployed changeset breaks checksum validation).
     */
    private static final String ROLLBACK_REQUIRED_FROM = "2026-09-18";

    private static final Pattern LC_MUW_DATED =
            Pattern.compile("^lc-muw-(\\d{4}-\\d{2}-\\d{2})-.*\\.xml$");

    @Test
    void applyingTheChangelogTwiceIsANoOp() throws Exception {
        int before = changelogRowCount();
        assertTrue(before > 0, "the base class should already have migrated the schema");

        SpringLiquibase again = new SpringLiquibase();
        again.setDataSource(DATA_SOURCE);
        again.setChangeLog("classpath:migration/master.xml");
        again.setResourceLoader(new DefaultResourceLoader());
        again.afterPropertiesSet();

        assertEquals(before, changelogRowCount(),
                "a second run of master.xml applied changesets — a changeset is not idempotent");
    }

    @Test
    void everyInstitutionalChangelogIsIncludedFromMaster() throws Exception {
        Path dir = migrationDir();
        String master = Files.readString(dir.resolve("master.xml"));
        List<String> orphans = new ArrayList<>();
        for (Path f : institutionalChangelogs(dir)) {
            if (!master.contains(f.getFileName().toString())) {
                orphans.add(f.getFileName().toString());
            }
        }
        assertTrue(orphans.isEmpty(),
                "these lc-muw changelogs exist but are never included from master.xml, "
                        + "so they never run: " + orphans);
    }

    @Test
    void newInstitutionalChangelogsDeclareARollback() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Path f : institutionalChangelogs(migrationDir())) {
            Matcher m = LC_MUW_DATED.matcher(f.getFileName().toString());
            if (!m.matches()) continue;
            if (m.group(1).compareTo(ROLLBACK_REQUIRED_FROM) < 0) continue; // grandfathered
            if (!Files.readString(f).contains("<rollback")) {
                missing.add(f.getFileName().toString());
            }
        }
        assertTrue(missing.isEmpty(),
                "changelogs dated " + ROLLBACK_REQUIRED_FROM + " or later must declare a "
                        + "<rollback> so the staging-clone dry run can be rewound: " + missing);
    }

    /** Guard the guard: if the layout moves, the two scans above must not silently pass. */
    @Test
    void theMigrationDirectoryIsWhereWeThinkItIs() throws Exception {
        List<Path> files = institutionalChangelogs(migrationDir());
        assertFalse(files.isEmpty(), "found no lc-muw-*.xml changelogs to check");
    }

    /* ---------------- helpers ---------------- */

    private int changelogRowCount() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM databasechangelog")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Resolves whether surefire runs with basedir {@code web/} or the repo root. */
    private static Path migrationDir() {
        Path relative = Path.of("core/src/main/resources/migration");
        if (Files.isDirectory(relative)) return relative;
        Path fromModule = Path.of("../core/src/main/resources/migration");
        if (Files.isDirectory(fromModule)) return fromModule;
        throw new IllegalStateException(
                "cannot locate core/src/main/resources/migration from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> institutionalChangelogs(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("lc-muw-"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
    }
}
