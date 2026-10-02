/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The closed-paths default in {@code application.yml} is the record of which
 * retirement waves are closed (DR-018). These checks keep that record honest:
 * every entry must be a catalogue key (an unknown key is only logged and
 * ignored at startup, so a typo would silently leave a screen open), the
 * screens the SPA still depends on must never be closed, and a wave once
 * closed stays closed.
 */
class LegacyClosedPathsDefaultTest {

    /** Screens the SPA or the login flow still depends on; closing one breaks users. */
    private static final Set<String> MUST_STAY_OPEN = Set.of(
            "/MainMenu",       // the login success target
            "/Logout",         // the SPA's sign-out calls it
            "/CreateDataset",  // DatasetListView still links it
            "/SystemStatus");  // unauthenticated probe

    /** Wave 0 (2026-09-30): not needed, no SPA replacement planned. */
    private static final List<String> WAVE_0 = List.of(
            "/Enterprise", "/TechAdmin", "/AdminSystem", "/AuditDatabase",
            "/ListSubject", "/ListSubjectData", "/ViewSubject", "/UpdateSubject",
            "/RemoveSubject", "/RestoreSubject",
            "/CreateJobImport", "/UpdateJobImport", "/ViewImportJob", "/ViewLogMessage",
            "/PrintoutCertificate", "/DeleteEventCRF", "/ConfigurePasswordRequirements",
            "/pages/auth");

    /**
     * Wave 1 (2026-10-02): administration. Every screen here was open to
     * system administrators only, and each has an SPA replacement or a
     * recorded "not needed" in the retirement log.
     */
    private static final List<String> WAVE_1 = List.of(
            // users and accounts
            "/ListUserAccounts", "/CreateUserAccount", "/ViewUserAccount", "/EditUserAccount",
            "/SetUserRole", "/EditStudyUserRole", "/DeleteStudyUserRole", "/DeleteUser", "/UnLockUser",
            "/pages/admin/listLdapUsers", "/pages/admin/selectLdapUser",
            // security configuration, mail, login history, user audit
            "/Configure", "/SendTestEmail", "/AuditUserActivity", "/AuditUserActivityData", "/AuditLogUser",
            // studies
            "/ListStudy", "/RemoveStudy", "/RestoreStudy", "/DownloadStudyMetadata",
            // jobs
            "/ViewAllJobs", "/ViewJob", "/ViewSingleJob", "/PauseJob", "/CreateJobExport", "/UpdateJobExport",
            "/pages/listCurrentScheduledJobs", "/pages/listCurrentScheduledJobsData", "/pages/cancelScheduledJob",
            // CRF library (D5 implemented: the SPA admits Data Manager, CRC and Administrator)
            "/ListCRF", "/CreateCRF", "/CreateCRFVersion", "/InitCreateCRFVersion", "/RemoveCRF", "/RestoreCRF",
            "/InitUpdateCRF", "/UpdateCRF", "/RemoveCRFVersion", "/RestoreCRFVersion",
            "/DownloadVersionSpreadSheet", "/BatchCRFMigration",
            // study audit log (director, coordinator, sysadmin in legacy; the SPA adds the monitor)
            "/AuditLogStudy",
            "/DeleteCRFVersion");

    /**
     * Administration screens wave 1 leaves open, each with its reason (see the
     * retirement log): /ViewCRF (its "run all rules for this CRF" link has no
     * SPA counterpart), /ViewStudy (read by other roles from open pages) and
     * the study-scoped user screens (W3).
     */
    private static final List<String> WAVE_1_HELD_OPEN = List.of(
            "/ViewCRF", "/ViewStudy", "/ListStudyUser");

    private static final Pattern DEFAULT =
            Pattern.compile("(?m)^\\s*closedPaths:\\s*\\$\\{LIBRECLINICA_LEGACY_CLOSED_PATHS:([^}]*)}\\s*$");

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    @Test
    void everyDefaultEntryIsACatalogueKey() {
        List<String> notKeys = new ArrayList<>();
        for (String path : defaultClosedPaths()) {
            if (catalog.entry(path).isEmpty()) {
                notKeys.add(path);
            }
        }
        assertEquals(List.of(), notKeys, "closedPaths default lists entries that are not catalogue keys");
    }

    @Test
    void screensTheSpaStillNeedsStayOpen() {
        List<String> closed = defaultClosedPaths();
        for (String path : MUST_STAY_OPEN) {
            assertTrue(!closed.contains(path), path + " must not be closed yet");
        }
    }

    @Test
    void waveZeroStaysClosed() {
        List<String> closed = defaultClosedPaths();
        for (String path : WAVE_0) {
            assertTrue(closed.contains(path), "wave 0 screen reopened: " + path);
        }
    }

    @Test
    void waveOneIsClosed() {
        List<String> closed = defaultClosedPaths();
        List<String> open = new ArrayList<>();
        for (String path : WAVE_1) {
            if (!closed.contains(path)) {
                open.add(path);
            }
        }
        assertEquals(List.of(), open, "wave 1 screens that are still open");
    }

    @Test
    void waveOneHoldsBackTheScreensOtherRolesStillUse() {
        List<String> closed = defaultClosedPaths();
        List<String> shut = new ArrayList<>();
        for (String path : WAVE_1_HELD_OPEN) {
            if (closed.contains(path)) {
                shut.add(path);
            }
        }
        assertEquals(List.of(), shut, "screens held open for the legacy Data Manager and other roles were closed");
    }

    @Test
    void everyClosedScreenNamesAnSpaReplacementOrIsRecordedAsNotNeeded() {
        // Not needed, by decision (plan R1.5: LDAP, replaced by SSO): no route to name.
        Set<String> notNeeded = Set.of("/pages/admin/listLdapUsers", "/pages/admin/selectLdapUser");
        List<String> unnamed = new ArrayList<>();
        for (String path : WAVE_1) {
            if (!notNeeded.contains(path) && !catalog.entry(path).orElseThrow().hasSpaRoute()) {
                unnamed.add(path);
            }
        }
        assertEquals(List.of(), unnamed, "wave 1 screens whose 410 names no SPA route");
    }

    /** The default of {@code libreclinica.legacy.closedPaths}, read from the one line that sets it. */
    private static List<String> defaultClosedPaths() {
        try (InputStream in = LegacyClosedPathsDefaultTest.class.getResourceAsStream("/application.yml")) {
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = DEFAULT.matcher(yml);
            assertTrue(m.find(), "application.yml has no closedPaths: ${LIBRECLINICA_LEGACY_CLOSED_PATHS:...} line");
            return LegacyServletTelemetryFilter.parseClosedPaths(m.group(1));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
