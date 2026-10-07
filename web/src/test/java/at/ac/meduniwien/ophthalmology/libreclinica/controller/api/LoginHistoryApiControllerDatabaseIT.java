/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.ClinicZone;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * R1.1 — the login history over {@code audit_user_login}, against a real
 * PostgreSQL with the production changelog applied.
 *
 * <p>The fixture is more than two pages: 64 attempts under names starting
 * {@code lh-it-}, across every status including the SSO codes 6 and 7. Every
 * request filters on that prefix, so the counts are this class's alone.
 *
 * <p>Pinned: only a system administrator reads it; pages come newest first,
 * do not overlap, and carry the total of the whole filtered result; the user,
 * status and date filters narrow the result on the server, a date being a day
 * in the clinic's zone; a {@code %} in the user filter is a character, not a
 * wildcard; malformed filters are refused by field; and the CSV export holds
 * the whole filtered result, not a page, with readable statuses and formula
 * cells neutralised, and leaves an audit row naming what was exported.
 */
class LoginHistoryApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "/api/v1/admin/login-history";

    /** Every fixture name contains this; each request filters on it. */
    private static final String PREFIX = "lh-it-";

    private static final int ALICE = 40;
    private static final int BOB_FAILED = 15;
    private static final int TOTAL = ALICE + BOB_FAILED + 1 + 5 + 2 + 1;

    /** The export's header, as consumers of the file read it. */
    private static final String CSV_HEADER = "attemptedAtUtc,userName,userAccountId,status,statusCode,details";

    /** The clinic's calendar day the two boundary rows straddle. */
    private static final LocalDate BOUNDARY_DAY = LocalDate.of(2026, 3, 10);

    @BeforeAll
    static void seed() throws Exception {
        Instant t0 = Instant.parse("2026-03-01T08:00:00Z");
        try (Connection c = DATA_SOURCE.getConnection()) {
            // 40 attempts an hour apart, logins and logouts alternating.
            for (int i = 0; i < ALICE; i++) {
                insert(c, PREFIX + "alice", 1, t0.plusSeconds(3600L * i), i % 2 == 0 ? 1 : 4, null);
            }
            // Failed logins, then one refused because the account was locked.
            Instant t1 = Instant.parse("2026-03-05T10:00:00Z");
            for (int i = 0; i < BOB_FAILED; i++) {
                insert(c, PREFIX + "bob", null, t1.plusSeconds(60L * i), 2, null);
            }
            insert(c, PREFIX + "bob", null, t1.plusSeconds(3600), 3, null);
            // SSO: three logins, two refused.
            Instant t2 = Instant.parse("2026-03-07T09:00:00Z");
            for (int i = 0; i < 3; i++) {
                insert(c, PREFIX + "sso", 1, t2.plusSeconds(60L * i), 6, "sso-principal=lh-it-sso@meduniwien.ac.at");
            }
            for (int i = 0; i < 2; i++) {
                insert(c, PREFIX + "unknown-principal", null, t2.plusSeconds(600L + 60L * i), 7,
                        "sso-principal=lh-it-unknown-principal sso-provider=shibboleth");
            }
            // Half an hour either side of midnight in the clinic's zone.
            ZoneId zone = ClinicZone.zone();
            insert(c, PREFIX + "night", null,
                    BOUNDARY_DAY.atTime(LocalTime.of(23, 30)).atZone(zone).toInstant(), 1, null);
            insert(c, PREFIX + "night", null,
                    BOUNDARY_DAY.plusDays(1).atTime(LocalTime.of(0, 30)).atZone(zone).toInstant(), 1, null);
            // A name a spreadsheet would run as a formula.
            insert(c, "=SUM(1,2)" + PREFIX + "cmd", null, Instant.parse("2026-03-12T12:00:00Z"), 2, null);
        }
    }

    private static void insert(Connection c, String user, Integer accountId, Instant at, int code,
                               String details) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_user_login (user_name, user_account_id, login_attempt_date, "
                        + "login_status_code, details, version) VALUES (?, ?, ?, ?, ?, 1)")) {
            ps.setString(1, user);
            if (accountId == null) ps.setNull(2, java.sql.Types.INTEGER);
            else ps.setInt(2, accountId);
            ps.setTimestamp(3, Timestamp.from(at));
            ps.setInt(4, code);
            ps.setString(5, details);
            ps.executeUpdate();
        }
    }

    /* ---- wiring ------------------------------------------------------- */

    private MockMvc mvc() {
        return ProductionMvc.standalone(new LoginHistoryApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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

    /** GET one page as the system administrator; {@code params} are name, value pairs. */
    private JsonNode page(String... params) throws Exception {
        String body = mvc().perform(withParams(get(BASE), params).session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    /** Parameters are set as values, so a {@code %} reaches the controller as itself. */
    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder req, String... params) {
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return req;
    }

    private static List<String> field(JsonNode page, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : page.get("rows")) out.add(row.get(name).asText());
        return out;
    }

    /* ---- the gate ----------------------------------------------------- */

    @Test
    void onlyASystemAdministratorReadsOrExportsTheHistory() throws Exception {
        for (String path : List.of(BASE, BASE + "/export.csv")) {
            mvc().perform(get(path).session(new MockHttpSession()))
                    .andExpect(status().isUnauthorized());
            mvc().perform(get(path).session(physician()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Sysadmin privilege required."));
        }
    }

    /* ---- paging ------------------------------------------------------- */

    @Test
    void pagesComeNewestFirstWithoutOverlapAndCarryTheTotal() throws Exception {
        JsonNode first = page("user", PREFIX, "pageSize", "25", "page", "0");
        JsonNode second = page("user", PREFIX, "pageSize", "25", "page", "1");
        JsonNode third = page("user", PREFIX, "pageSize", "25", "page", "2");

        for (JsonNode p : List.of(first, second, third)) {
            assertEquals(TOTAL, p.get("totalCount").asLong(), "every page carries the whole total");
            assertEquals(25, p.get("pageSize").asInt());
        }
        assertEquals(25, first.get("rows").size());
        assertEquals(25, second.get("rows").size());
        assertEquals(TOTAL - 50, third.get("rows").size());

        List<String> times = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (JsonNode p : List.of(first, second, third)) {
            times.addAll(field(p, "attemptedAt"));
            for (JsonNode row : p.get("rows")) ids.add(row.get("id").asLong());
        }
        assertEquals(TOTAL, ids.size(), "three pages hold every row once");
        for (int i = 1; i < times.size(); i++) {
            assertTrue(Instant.parse(times.get(i - 1)).compareTo(Instant.parse(times.get(i))) >= 0,
                    "newest first across page boundaries: " + times.get(i - 1) + " then " + times.get(i));
        }
        assertEquals("2026-03-12T12:00:00Z", times.get(0));
    }

    @Test
    void aPageBeyondTheEndIsEmptyButStillCounts() throws Exception {
        JsonNode beyond = page("user", PREFIX, "pageSize", "25", "page", "9");
        assertEquals(TOTAL, beyond.get("totalCount").asLong());
        assertEquals(0, beyond.get("rows").size());
    }

    /* ---- filters ------------------------------------------------------ */

    @Test
    void theUserFilterIsAContainsMatchIgnoringCase() throws Exception {
        JsonNode bob = page("user", "LH-IT-BOB");
        assertEquals(BOB_FAILED + 1, bob.get("totalCount").asLong());
        assertTrue(field(bob, "userName").stream().allMatch((PREFIX + "bob")::equals));
    }

    @Test
    void aWildcardInTheUserFilterIsMatchedLiterally() throws Exception {
        assertEquals(0, page("user", PREFIX + "%").get("totalCount").asLong(),
                "a percent sign is a character, which no fixture name contains");
        assertEquals(0, page("user", "lh_it").get("totalCount").asLong(),
                "an underscore is not a single-character wildcard");
    }

    @Test
    void theStatusFilterSelectsTheSsoCodes() throws Exception {
        JsonNode sso = page("user", PREFIX, "status", "SSO_LOGIN,SSO_LOGIN_FAILED");
        assertEquals(5, sso.get("totalCount").asLong());
        assertEquals(List.of("SSO_LOGIN_FAILED", "SSO_LOGIN_FAILED", "SSO_LOGIN", "SSO_LOGIN", "SSO_LOGIN"),
                field(sso, "status"));
        assertEquals(List.of("7", "7", "6", "6", "6"), field(sso, "statusCode"));
        assertTrue(sso.get("rows").get(0).get("details").asText().contains("sso-provider=shibboleth"));

        JsonNode failed = page("user", PREFIX, "status", "FAILED_LOGIN", "status", "FAILED_LOGIN_LOCKED");
        assertEquals(BOB_FAILED + 1 + 1, failed.get("totalCount").asLong(),
                "repeated status parameters combine: bob's failures, his locked refusal, and the formula name");
    }

    @Test
    void aDayIsACalendarDayInTheClinicsZone() throws Exception {
        String day = BOUNDARY_DAY.toString();
        String next = BOUNDARY_DAY.plusDays(1).toString();

        JsonNode onTheDay = page("user", PREFIX + "night", "from", day, "to", day);
        assertEquals(1, onTheDay.get("totalCount").asLong(), "23:30 local belongs to the day");
        JsonNode theNextDay = page("user", PREFIX + "night", "from", next, "to", next);
        assertEquals(1, theNextDay.get("totalCount").asLong(), "00:30 local belongs to the next day");
        JsonNode both = page("user", PREFIX + "night", "from", day, "to", next);
        assertEquals(2, both.get("totalCount").asLong(), "from and to are both inclusive");

        JsonNode bobsDay = page("user", PREFIX, "from", "2026-03-05", "to", "2026-03-05");
        assertEquals(BOB_FAILED + 1, bobsDay.get("totalCount").asLong());
    }

    @Test
    void malformedFiltersAreRefusedByField() throws Exception {
        mvc().perform(withParams(get(BASE), "status", "NOT_A_STATUS").session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc().perform(withParams(get(BASE), "from", "2026-13-01").session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc().perform(withParams(get(BASE), "from", "2026-03-10", "to", "2026-03-09").session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        mvc().perform(withParams(get(BASE + "/export.csv"), "to", "yesterday").session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
    }

    /* ---- export ------------------------------------------------------- */

    @Test
    void theExportHoldsTheWholeFilteredResultAndIsAudited() throws Exception {
        long auditBefore = exportAuditRows();

        MockHttpServletResponse res = mvc().perform(withParams(get(BASE + "/export.csv"), "user", PREFIX).session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertTrue(Objects.requireNonNull(res.getContentType()).startsWith("text/csv"), res.getContentType());
        assertTrue(Objects.requireNonNull(res.getHeader("Content-Disposition")).startsWith("attachment; filename=\"login-history_"));
        String[] lines = res.getContentAsString().split("\r\n");
        assertEquals(CSV_HEADER, lines[0]);
        assertEquals(TOTAL + 1, lines.length, "every filtered row, not one page of 50");

        String csv = res.getContentAsString();
        assertTrue(csv.contains(",SSO login refused,7,"), "SSO refusals read as words");
        assertTrue(csv.contains(",SSO login,6,"));
        assertTrue(csv.contains(",Refused: account locked,3,"));

        assertEquals(auditBefore + 1, exportAuditRows());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, new_value FROM audit_log_event WHERE audit_log_event_type_id = 55 "
                             + "AND audit_table = 'audit_user_login' ORDER BY audit_id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("user_id"));
            assertEquals("rows=" + TOTAL + " user=" + PREFIX, rs.getString("new_value"));
        }
    }

    @Test
    void theExportAppliesTheFilters() throws Exception {
        String csv = mvc().perform(withParams(get(BASE + "/export.csv"), "user", PREFIX, "status", "SSO_LOGIN_FAILED")
                        .session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length, csv);
        assertTrue(lines[1].contains(PREFIX + "unknown-principal"));
    }

    @Test
    void aNameASpreadsheetWouldRunAsAFormulaIsExportedAsText() throws Exception {
        String csv = mvc().perform(withParams(get(BASE + "/export.csv"), "user", "=SUM(").session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length, csv);
        // A leading apostrophe makes it text; the comma makes it quoted.
        assertTrue(lines[1].contains(",\"'=SUM(1,2)lh-it-cmd\","), lines[1]);
    }

    @Test
    void theListNamesEachStatus() throws Exception {
        JsonNode all = page("user", PREFIX, "pageSize", String.valueOf(TOTAL));
        assertEquals(Set.of("SUCCESSFUL_LOGIN", "SUCCESSFUL_LOGOUT", "FAILED_LOGIN", "FAILED_LOGIN_LOCKED",
                        "SSO_LOGIN", "SSO_LOGIN_FAILED"),
                new HashSet<>(field(all, "status")));
        mvc().perform(withParams(get(BASE), "user", PREFIX + "alice", "pageSize", "1").session(sysadmin()))
                .andExpect(jsonPath("$.rows[0].userAccountId").value(1))
                .andExpect(jsonPath("$.rows[0].attemptedAt", Matchers.endsWith("Z")));
    }

    private static long exportAuditRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = 55 "
                             + "AND audit_table = 'audit_user_login'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
