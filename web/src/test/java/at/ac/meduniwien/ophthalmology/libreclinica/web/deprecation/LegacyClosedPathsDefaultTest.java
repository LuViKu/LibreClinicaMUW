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
 * screens the SPA still depends on must never be closed, a wave once closed
 * stays closed, and a deleted wave stays deleted.
 */
class LegacyClosedPathsDefaultTest {

    /**
     * The shell and the public forms, open until the shell wave (W5) removes
     * what still leads there: logins, SSO and an expired session land on
     * /MainMenu, and e-mail links use sysURL. Closing one breaks users.
     */
    private static final Set<String> MUST_STAY_OPEN = Set.of(
            "/MainMenu", "/ChangeStudy", "/Logout", "/UpdateProfile", "/ResetPassword",
            "/MatchPassword", "/RequestStudy",
            "/RequestAccount", "/Contact");

    /** The catalogue areas of {@link #MUST_STAY_OPEN}; every other area is closed in wave 2. */
    private static final Set<LegacyServletDeprecationCatalog.Bucket> OPEN_AREAS = Set.of(
            LegacyServletDeprecationCatalog.Bucket.SHELL_AND_LOGIN,
            LegacyServletDeprecationCatalog.Bucket.SUPPORT_FORMS);

    /**
     * Waves 0 (2026-09-30, not needed) and 1 (2026-10-02, administration),
     * deleted after their bake-in (DR-018 amendment, 2026-11-13): their
     * servlets, pages and catalogue keys are gone. Listing one of them in the
     * default again would only be reported at startup and ignored, so it must
     * not come back, and neither may its catalogue key.
     */
    private static final List<String> DELETED = List.of(
            // wave 0
            "/Enterprise", "/TechAdmin", "/AdminSystem", "/AuditDatabase",
            "/ListSubject", "/ListSubjectData", "/ViewSubject", "/UpdateSubject",
            "/RemoveSubject", "/RestoreSubject",
            "/CreateJobImport", "/UpdateJobImport", "/ViewImportJob", "/ViewLogMessage",
            "/PrintoutCertificate", "/DeleteEventCRF", "/ConfigurePasswordRequirements",
            // wave 0: the OpenClinica 3.x REST API, deleted separately
            "/pages/auth",
            // wave 1: users and accounts
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
            // CRF library
            "/ListCRF", "/CreateCRF", "/CreateCRFVersion", "/InitCreateCRFVersion", "/RemoveCRF", "/RestoreCRF",
            "/InitUpdateCRF", "/UpdateCRF", "/RemoveCRFVersion", "/RestoreCRFVersion",
            "/DownloadVersionSpreadSheet", "/BatchCRFMigration",
            // study audit log
            "/AuditLogStudy",
            "/DeleteCRFVersion");

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
    void theDeletedWavesAreGoneFromTheCatalogueAndTheDefault() {
        List<String> closed = defaultClosedPaths();
        List<String> left = new ArrayList<>();
        for (String path : DELETED) {
            if (catalog.entry(path).isPresent() || closed.contains(path)) {
                left.add(path);
            }
        }
        assertEquals(List.of(), left, "deleted screens still in the catalogue or the closed-paths default");
    }

    /**
     * Wave 2 (2026-11-13, DR-018 amendment): everything but the shell and the
     * public forms, in one wave. The legacy-access log showed no request for
     * any of these screens by anyone but a system administrator since
     * telemetry began, so the per-area waves W2 to W4 were folded into one.
     */
    @Test
    void waveTwoClosesEverythingButTheShell() {
        List<String> closed = defaultClosedPaths();
        List<String> open = new ArrayList<>();
        for (LegacyServletDeprecationCatalog.Entry entry : catalog.all().values()) {
            if (!OPEN_AREAS.contains(entry.bucket()) && !closed.contains(entry.legacyPath())) {
                open.add(entry.legacyPath());
            }
        }
        assertEquals(List.of(), open, "screens outside the shell that are still open");
    }

    @Test
    void theShellIsExactlyWhatStaysOpen() {
        Set<String> shell = new java.util.TreeSet<>();
        for (LegacyServletDeprecationCatalog.Entry entry : catalog.all().values()) {
            if (OPEN_AREAS.contains(entry.bucket()) && !defaultClosedPaths().contains(entry.legacyPath())) {
                shell.add(entry.legacyPath());
            }
        }
        assertEquals(new java.util.TreeSet<>(MUST_STAY_OPEN), shell);
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
