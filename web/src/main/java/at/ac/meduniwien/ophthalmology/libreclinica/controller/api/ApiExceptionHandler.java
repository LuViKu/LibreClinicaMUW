/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.SQLException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Phase E.5 #6 — global exception handler scoped to the {@code
 * controller/api/} package.
 *
 * <p>The {@code /pages/api/v1/**} adapters ship a JSON wire contract;
 * the SPA's {@code api/client.ts} ApiError model parses
 * {@code { message }} bodies on non-2xx responses. When a handler
 * method throws (NPE from a stub-DataSource in MockMvc, SQLException
 * from a DAO under real load, parse failure from a malformed JSON
 * body, etc.) Spring MVC's default machinery either returns 500 with
 * an HTML stack trace or — under {@code standaloneSetup} in tests —
 * re-throws inside {@code ServletException}. Neither matches the
 * SPA's expectation. This advice translates both into a 5xx JSON body
 * the SPA can parse uniformly.
 *
 * <p>Scope: {@code @RestControllerAdvice(basePackages = …)} pins this
 * advice to the new API controllers only. The 295 legacy
 * JSP-rendering servlets keep their existing error-page behaviour;
 * the SPA's adapters get JSON.
 *
 * <p>The wrapped DTOs deliberately carry only the human-readable
 * {@code message} field — no class names, no stack traces. Detailed
 * cause/stack lives in the server-side log via {@code LOG.error(...)}.
 * That keeps the wire contract simple and avoids leaking implementation
 * details to a clinical-data client.
 *
 * <p>The single recognised "client made a mistake" branch is the 400
 * for {@link MissingServletRequestParameterException} +
 * {@link MethodArgumentTypeMismatchException} +
 * {@link HttpMessageNotReadableException}; everything else is treated
 * as a server-side fault.
 */
@RestControllerAdvice(basePackages = "at.ac.meduniwien.ophthalmology.libreclinica.controller.api")
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Malformed request body / missing required param / wrong type. */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class,
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        LOG.debug("API 400 bad request: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage() == null ? "Bad request" : e.getMessage()));
    }

    /** SQL failures from the DAO layer (connection issues, constraint violations, etc.). */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSql(SQLException e) {
        LOG.error("API 500 from SQLException: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Database access failed; the operation could not complete."));
    }

    /**
     * Catch-all for everything else (NullPointerException, RuntimeException,
     * anything not handled above). Returns a generic 500 + logs the full
     * stack trace server-side. This is what catches the MockMvc-stub
     * DataSource NPE that A2's audit test deliberately triggers — with
     * this advice in place the test asserts the wrapped 500 JSON, no
     * ServletException re-throw needed.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Throwable e) {
        LOG.error("API 500 unexpected: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Internal server error."));
    }
}
