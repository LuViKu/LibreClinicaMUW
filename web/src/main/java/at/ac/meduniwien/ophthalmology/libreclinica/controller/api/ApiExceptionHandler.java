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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;

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
 *
 * <p>Phase E-hardening B5 — body shape canonicalised to {@link
 * ValidationErrorBody}. Framework exceptions don't carry per-field
 * validation details, so {@code errors} is always {@code List.of()}.
 * Wire shape changes from {@code {"message": "…"}} to
 * {@code {"message": "…", "errors": []}}; the SPA's
 * {@code ApiError.parseBody} is extra-field tolerant (Jackson +
 * JSON.parse ignore unknown fields by default), so this is a
 * non-breaking change.
 */
@RestControllerAdvice(basePackages = "at.ac.meduniwien.ophthalmology.libreclinica.controller.api")
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** What a client is told when the request body cannot be read. */
    static final String UNREADABLE_BODY_MESSAGE = "The request body is missing or is not valid JSON of the expected shape.";

    /** Malformed request body / missing required param / wrong type. */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class,
    })
    public ResponseEntity<ValidationErrorBody> handleBadRequest(Exception e) {
        LOG.debug("API 400 bad request: {}", e.getMessage());
        // Fixed messages for the framework exceptions: Spring's text for an
        // empty body embeds the controller method signature and Jackson's
        // embeds parser internals; neither belongs on the wire.
        String message;
        if (e instanceof HttpMessageNotReadableException) {
            message = "Malformed or missing request body.";
        } else if (e instanceof MethodArgumentTypeMismatchException mm) {
            message = "Invalid value for parameter '" + mm.getName() + "'.";
        } else if (e instanceof MissingServletRequestParameterException mp) {
            message = "Missing required request parameter: '" + mp.getParameterName() + "'.";
        } else {
            message = e.getMessage() == null ? "Bad request" : e.getMessage();
        }
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorBody(message, List.of()));
    }

    /**
     * A request body that is not JSON, not the expected shape, or absent.
     *
     * <p>The exception's own message is the parser's ("JSON parse error:
     * Unexpected end-of-input ...", or the controller method signature for a
     * missing body), which differs between Jackson versions and names internals;
     * the response says the same thing whichever library reads the body. The
     * cause is in the debug log.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ValidationErrorBody> handleUnreadableBody(HttpMessageNotReadableException e) {
        LOG.debug("API 400 unreadable request body: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorBody(UNREADABLE_BODY_MESSAGE, List.of()));
    }

    /** SQL failures from the DAO layer (connection issues, constraint violations, etc.). */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ValidationErrorBody> handleSql(SQLException e) {
        LOG.error("API 500 from SQLException: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ValidationErrorBody(
                        "Database access failed; the operation could not complete.",
                        List.of()));
    }

    /* ====================================================================== */
    /* Phase E-hardening A3 — five additional handlers covering AuthN/AuthZ,  */
    /* header validation, integrity collisions, and upload-size cliffs.       */
    /* Placed before the catch-all {@link Throwable} handler so the more-     */
    /* specific subclass dispatch wins. Body shape canonicalised to {@link    */
    /* ValidationErrorBody} in B5 — the SPA's ApiError parser tolerates the   */
    /* extra empty errors: [] field.                                          */
    /* ====================================================================== */

    /**
     * Spring Security denial: the user is authenticated but their role
     * lacks the permission the endpoint requires. 4xx → WARN.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ValidationErrorBody> handleAccessDenied(AccessDeniedException e) {
        LOG.warn("API 403 access denied: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ValidationErrorBody(
                        "Forbidden — your role can't perform this action.",
                        List.of()));
    }

    /**
     * Spring Security: no authentication / expired session / invalid
     * credentials. 4xx → WARN.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ValidationErrorBody> handleAuthentication(AuthenticationException e) {
        LOG.warn("API 401 authentication failure: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new ValidationErrorBody(
                        "Not authenticated — please sign in again.",
                        List.of()));
    }

    /**
     * A {@code @RequestHeader}-bound parameter the controller declared
     * was not present on the request. Echo the missing header name so
     * the SPA can surface "X-Foo header required" without parsing a
     * stack trace. 4xx → WARN.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ValidationErrorBody> handleMissingHeader(MissingRequestHeaderException e) {
        LOG.warn("API 400 missing request header: {}", e.getHeaderName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorBody(
                        "Missing required request header: '" + e.getHeaderName() + "'.",
                        List.of()));
    }

    /**
     * DB-layer integrity violation (unique constraint, FK constraint,
     * not-null violation, …). The retrospective-backfill flow can hit
     * this on duplicate-OID collisions when re-importing paper records;
     * surfacing 409 lets the SPA show "this subject OID already exists"
     * instead of a generic 500. 4xx → WARN.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ValidationErrorBody> handleDataIntegrity(DataIntegrityViolationException e) {
        LOG.warn("API 409 data integrity violation: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ValidationErrorBody(
                        "Data integrity violation — see request body for the conflict.",
                        List.of()));
    }

    /**
     * Multipart upload exceeding the configured cap. 4xx → WARN. The
     * SPA's upload widgets already check the limit client-side, so this
     * only fires on direct API misuse or proxy stripping the size header.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ValidationErrorBody> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        LOG.warn("API 413 upload exceeds maximum allowed size: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ValidationErrorBody(
                        "Upload exceeds the maximum allowed size.",
                        List.of()));
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
    public ResponseEntity<ValidationErrorBody> handleUnexpected(Throwable e) {
        // Exceptions that already carry their own HTTP status keep it. This
        // advice runs ahead of ResponseStatusExceptionResolver and
        // DefaultHandlerExceptionResolver, so without these branches every
        // 404/405/415 (ErrorResponse: ResponseStatusException,
        // HttpRequestMethodNotSupportedException, ...) and every
        // @ResponseStatus exception would collapse into a 500.
        if (e instanceof ErrorResponse er) {
            return statusBody(er.getStatusCode(), er.getBody().getDetail(), e);
        }
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class);
        if (rs != null) {
            return statusBody(rs.code(), rs.reason().isEmpty() ? null : rs.reason(), e);
        }
        LOG.error("API 500 unexpected: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ValidationErrorBody("Internal server error.", List.of()));
    }

    private static ResponseEntity<ValidationErrorBody> statusBody(HttpStatusCode status, String detail, Throwable e) {
        if (status.is5xxServerError()) {
            LOG.error("API {} from {}: {}", status.value(), e.getClass().getSimpleName(), e.getMessage(), e);
        } else {
            LOG.debug("API {} from {}: {}", status.value(), e.getClass().getSimpleName(), e.getMessage());
        }
        String message = detail != null && !detail.isBlank()
                ? detail
                : (status instanceof HttpStatus hs ? hs.getReasonPhrase() : "Request failed");
        return ResponseEntity.status(status).body(new ValidationErrorBody(message, List.of()));
    }
}
