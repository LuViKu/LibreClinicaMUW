/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.core.ClinicZone;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * R1.1 (2026-09-30) — the login history, SPA replacement for the legacy
 * {@code /AuditUserActivity} page.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/login-history} — one page of
 *       {@code audit_user_login}, newest first, with the size of the whole
 *       filtered result.</li>
 *   <li>{@code GET /api/v1/admin/login-history/export.csv} — the whole
 *       filtered result as CSV, not one page.</li>
 * </ul>
 *
 * <p>{@code audit_user_login} is the only record of who logged in, logged
 * out or failed to log in: one row per attempt, including the SSO codes 6
 * and 7 of Phase D.5 (DR-014). The legacy page loads a fixed 500 rows with
 * no filter, so older attempts could not be reached at all.
 *
 * <p>Filters, all optional and combined with AND:
 * <ul>
 *   <li>{@code user} — part of the user name, ignoring case. A failed login
 *       is recorded under the name that was entered, which need not be an
 *       account.</li>
 *   <li>{@code status} — one or more {@link LoginStatus} names, repeated or
 *       comma-separated.</li>
 *   <li>{@code from}, {@code to} — calendar days ({@code YYYY-MM-DD}), both
 *       inclusive, in the clinic's zone ({@link ClinicZone}).</li>
 * </ul>
 *
 * <p>Plain SQL rather than {@code AuditUserLoginDao}: the DAO pages, but its
 * date filter matches a prefix ({@code 2026-09}) rather than a range, and
 * its criteria queries run only inside the request-scoped entity manager.
 * The page order is the index {@code audit_user_login_attempt_date_idx}
 * ({@code lc-muw-2026-09-30-login-history-index.xml}).
 *
 * <p>System administrators only: anonymous → 401, anyone else → 403, as in
 * {@link AdminApiController}.
 */
@RestController
@RequestMapping("/api/v1/admin/login-history")
@Tag(name = "Admin tooling",
     description = "Sysadmin-only diagnostic + configuration surfaces — SPA replacement for the legacy admin JSPs.")
public class LoginHistoryApiController {

    private static final Logger LOG = LoggerFactory.getLogger(LoginHistoryApiController.class);

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 500;

    /** {@code audit_user_login.user_name} is VARCHAR(255). */
    static final int MAX_USER_FILTER_LENGTH = 255;

    /** Rows the export fetches from the cursor at a time. */
    private static final int EXPORT_FETCH_SIZE = 500;

    static final String CSV_HEADER = "attemptedAtUtc,userName,userAccountId,status,statusCode,details";

    private static final String CRLF = "\r\n";

    private static final String SELECT =
            "SELECT id, user_name, user_account_id, login_attempt_date, login_status_code, details"
                    + " FROM audit_user_login";

    /** Newest first; the id breaks ties, so a page boundary never repeats or skips a row. */
    private static final String ORDER = " ORDER BY login_attempt_date DESC, id DESC";

    private final DataSource dataSource;

    @Autowired
    public LoginHistoryApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * One login attempt. {@code status} is the {@link LoginStatus} name, or
     * {@code UNKNOWN} for a code the enum does not know; {@code attemptedAt}
     * is an ISO-8601 instant (UTC).
     */
    public record LoginAttemptDto(
            long id,
            String userName,
            Integer userAccountId,
            String attemptedAt,
            String status,
            Integer statusCode,
            String details) {}

    /** {@code totalCount} counts the whole filtered result; {@code rows} is one page of it. */
    public record LoginHistoryPage(
            long totalCount,
            int page,
            int pageSize,
            List<LoginAttemptDto> rows) {}

    /* ====================================================================== */
    /* GET — one page                                                         */
    /* ====================================================================== */

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        List<FieldError> errors = new ArrayList<>();
        Filter filter = Filter.parse(user, status, from, to, ClinicZone.zone(), errors);
        if (filter == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody("Validation failed.", errors));
        }
        int p = (page == null || page < 0) ? 0 : page;
        int ps = (pageSize == null || pageSize <= 0) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        try (Connection c = dataSource.getConnection()) {
            long total = count(c, filter);
            List<LoginAttemptDto> rows = new ArrayList<>(ps);
            try (PreparedStatement st = c.prepareStatement(SELECT + filter.where() + ORDER + " LIMIT ? OFFSET ?")) {
                int i = filter.bind(st, 1);
                st.setInt(i++, ps);
                st.setLong(i, (long) p * ps);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) rows.add(read(rs));
                }
            }
            return ResponseEntity.ok(new LoginHistoryPage(total, p, ps, rows));
        } catch (SQLException e) {
            LOG.error("Failed to read the login history: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to read the login history — see server log."));
        }
    }

    /* ====================================================================== */
    /* GET — the whole filtered result as CSV                                 */
    /* ====================================================================== */

    /**
     * Streams every row the filters select, whatever the page size, through
     * a database cursor so a long history is never held in memory. The
     * export is recorded in {@code audit_log_event} like the audit-log and
     * discrepancy exports. Errors before the first byte answer as JSON, like
     * every other endpoint here.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<?> exportCsv(
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            HttpSession session,
            HttpServletResponse response) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        List<FieldError> errors = new ArrayList<>();
        Filter filter = Filter.parse(user, status, from, to, ClinicZone.zone(), errors);
        if (filter == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody("Validation failed.", errors));
        }
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");

        long rows = 0;
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            // PostgreSQL reads through a cursor only inside a transaction;
            // with autocommit on, the driver loads the whole result first.
            c.setAutoCommit(false);
            try (PreparedStatement st = c.prepareStatement(SELECT + filter.where() + ORDER)) {
                st.setFetchSize(EXPORT_FETCH_SIZE);
                filter.bind(st, 1);
                try (ResultSet rs = st.executeQuery()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("text/csv;charset=UTF-8");
                    response.setHeader("Content-Disposition",
                            "attachment; filename=\"" + exportFilename() + "\"");
                    Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
                    out.write(CSV_HEADER + CRLF);
                    while (rs.next()) {
                        out.write(csvRow(read(rs)));
                        rows++;
                    }
                    out.flush();
                }
            } finally {
                c.rollback();
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException | IOException e) {
            LOG.error("Login-history export failed after {} rows: {}", rows, e.getMessage(), e);
            if (!response.isCommitted()) {
                response.reset();
                return ResponseEntity.status(500).body(Map.of(
                        "message", "Failed to export the login history — see server log."));
            }
            // Part of the file has gone out; nothing better to do than stop.
            return null;
        }
        auditExport(ub.getId(), filter.describe(rows));
        return null;
    }

    /* ====================================================================== */
    /* Filters                                                                */
    /* ====================================================================== */

    /** The validated filters, as a WHERE clause and the values it binds. */
    static final class Filter {

        final String user;
        final List<LoginStatus> statuses;
        final LocalDate from;
        final LocalDate to;
        private final Timestamp fromInclusive;
        private final Timestamp toExclusive;

        private Filter(String user, List<LoginStatus> statuses, LocalDate from, LocalDate to, ZoneId zone) {
            this.user = user;
            this.statuses = statuses;
            this.from = from;
            this.to = to;
            // A day in the clinic's zone is [its midnight, the next midnight).
            this.fromInclusive = from == null ? null : Timestamp.from(from.atStartOfDay(zone).toInstant());
            this.toExclusive = to == null ? null : Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        }

        /** The filters, or {@code null} with {@code errors} filled if any is malformed. */
        static Filter parse(String user, List<String> status, String from, String to,
                            ZoneId zone, List<FieldError> errors) {
            String name = user == null || user.isBlank() ? null : user.trim();
            if (name != null && name.length() > MAX_USER_FILTER_LENGTH) {
                errors.add(new FieldError("user",
                        "user must be at most " + MAX_USER_FILTER_LENGTH + " characters."));
            }
            Set<LoginStatus> statuses = new LinkedHashSet<>();
            if (status != null) {
                for (String raw : status) {
                    if (raw == null || raw.isBlank()) continue;
                    try {
                        statuses.add(LoginStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException unknown) {
                        errors.add(new FieldError("status", "Unknown status '" + raw.trim()
                                + "'. Expected one of " + Arrays.stream(LoginStatus.values())
                                        .map(Enum::name).collect(Collectors.joining(", ")) + "."));
                    }
                }
            }
            LocalDate fromDay = parseDay("from", from, errors);
            LocalDate toDay = parseDay("to", to, errors);
            if (fromDay != null && toDay != null && toDay.isBefore(fromDay)) {
                errors.add(new FieldError("to", "to must not be before from."));
            }
            return errors.isEmpty() ? new Filter(name, List.copyOf(statuses), fromDay, toDay, zone) : null;
        }

        private static LocalDate parseDay(String field, String raw, List<FieldError> errors) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return LocalDate.parse(raw.trim());
            } catch (DateTimeParseException malformed) {
                errors.add(new FieldError(field, field + " must be a date as YYYY-MM-DD."));
                return null;
            }
        }

        String where() {
            List<String> terms = new ArrayList<>();
            if (user != null) terms.add("LOWER(user_name) LIKE ? ESCAPE '!'");
            if (!statuses.isEmpty()) {
                terms.add("login_status_code IN (" + String.join(", ", Collections.nCopies(statuses.size(), "?")) + ")");
            }
            if (fromInclusive != null) terms.add("login_attempt_date >= ?");
            if (toExclusive != null) terms.add("login_attempt_date < ?");
            return terms.isEmpty() ? "" : " WHERE " + String.join(" AND ", terms);
        }

        /** Binds the values of {@link #where()} from {@code index}; returns the next free index. */
        int bind(PreparedStatement st, int index) throws SQLException {
            int i = index;
            if (user != null) st.setString(i++, "%" + escapeLike(user.toLowerCase(Locale.ROOT)) + "%");
            for (LoginStatus s : statuses) st.setInt(i++, s.getCode());
            if (fromInclusive != null) st.setTimestamp(i++, fromInclusive);
            if (toExclusive != null) st.setTimestamp(i++, toExclusive);
            return i;
        }

        /** What an export asked for, for its audit row. */
        String describe(long rows) {
            StringBuilder sb = new StringBuilder("rows=").append(rows);
            if (user != null) sb.append(" user=").append(user);
            if (!statuses.isEmpty()) {
                sb.append(" status=").append(statuses.stream().map(Enum::name).collect(Collectors.joining(",")));
            }
            if (from != null) sb.append(" from=").append(from);
            if (to != null) sb.append(" to=").append(to);
            return sb.toString();
        }
    }

    /** The user filter is text, not a pattern: {@code %} and {@code _} match themselves. */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /* ====================================================================== */
    /* Rows                                                                   */
    /* ====================================================================== */

    private static long count(Connection c, Filter filter) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT COUNT(*) FROM audit_user_login" + filter.where())) {
            filter.bind(st, 1);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static LoginAttemptDto read(ResultSet rs) throws SQLException {
        int accountId = rs.getInt("user_account_id");
        Integer userAccountId = rs.wasNull() ? null : accountId;
        int code = rs.getInt("login_status_code");
        Integer statusCode = rs.wasNull() ? null : code;
        LoginStatus status = statusCode == null ? null : LoginStatus.getByCode(statusCode);
        Timestamp at = rs.getTimestamp("login_attempt_date");
        return new LoginAttemptDto(
                rs.getLong("id"),
                rs.getString("user_name"),
                userAccountId,
                at == null ? null : at.toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
                status == null ? "UNKNOWN" : status.name(),
                statusCode,
                rs.getString("details"));
    }

    /* ====================================================================== */
    /* CSV                                                                    */
    /* ====================================================================== */

    static String csvRow(LoginAttemptDto r) {
        return String.join(",",
                DiscrepancyExportCsv.quoteIfNeeded(r.attemptedAt()),
                DiscrepancyExportCsv.quoteIfNeeded(asText(r.userName())),
                r.userAccountId() == null ? "" : r.userAccountId().toString(),
                DiscrepancyExportCsv.quoteIfNeeded(statusLabel(r.statusCode())),
                r.statusCode() == null ? "" : r.statusCode().toString(),
                DiscrepancyExportCsv.quoteIfNeeded(asText(r.details()))) + CRLF;
    }

    /**
     * A spreadsheet reads a cell that starts with {@code = + - @}, a tab or a
     * carriage return as a formula. User names and details are whatever was
     * entered at the login form or sent by the SSO proxy, so such a cell gets
     * a leading apostrophe, which spreadsheets display as text.
     */
    static String asText(String s) {
        if (s == null || s.isEmpty()) return s;
        char first = s.charAt(0);
        boolean formula = first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
        return formula ? "'" + s : s;
    }

    /** English labels for the CSV; the SPA translates its own. */
    static String statusLabel(Integer code) {
        LoginStatus s = code == null ? null : LoginStatus.getByCode(code);
        if (s == null) return code == null ? "Unknown" : "Unknown (code " + code + ")";
        return switch (s) {
            case SUCCESSFUL_LOGIN -> "Successful login";
            case FAILED_LOGIN -> "Failed login";
            case FAILED_LOGIN_LOCKED -> "Refused: account locked";
            case SUCCESSFUL_LOGOUT -> "Logout";
            case ACCESS_CODE_VIEWED -> "Access code viewed";
            case SSO_LOGIN -> "SSO login";
            case SSO_LOGIN_FAILED -> "SSO login refused";
        };
    }

    private static String exportFilename() {
        return "login-history_" + ClinicZone.today().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
    }

    /**
     * The export, in the audit trail: type 55 (audit log exported), as the
     * audit-log XLSX export writes it, against {@code audit_user_login}, with
     * the filters and the row count. A failed insert is logged, not raised:
     * the file has already gone out.
     */
    private void auditExport(int userId, String summary) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                             + "audit_table, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'audit_user_login', 'login_history', '', ?)")) {
            st.setInt(1, AuditApiController.AUDIT_TYPE_AUDIT_LOG_EXPORTED);
            st.setInt(2, userId);
            st.setString(3, summary);
            st.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Could not audit the login-history export by user_id={}: {}", userId, e.getMessage());
        }
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private static ResponseEntity<?> requireSysadmin(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Sysadmin privilege required."));
        }
        return null;
    }
}
