/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web;

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

/**
 * DR-040 phase 2 — the ingest source a device request was made for.
 *
 * <p>{@link UploaderTokenFilter} reads the {@code X-MUW-Uploader-Token} header
 * of a request to a device endpoint, finds the uploader behind it and leaves
 * it on the request under {@link #ATTRIBUTE}. The controllers behind the filter
 * (and the rate limiter, which runs after it) read it back here and pass it on
 * explicitly from there; nothing keeps it in a thread-local.
 *
 * <p>A request attribute lives in the container, not on the wire: a client can
 * send any header it likes, but it cannot set this. The filter clears the
 * attribute before it decides anything on a guarded path, so a request that
 * is forwarded or re-dispatched never inherits a source it did not earn.
 *
 * <p>{@code null} means the request carries no source: an untokened request
 * the front door let in (exactly one active location, door not switched off),
 * a signed-in user, or a path the filter does not guard. Callers then do what
 * they always did.
 */
public final class UploaderSources {

    /** The request attribute holding the {@link IngestSourceService.Source}; also the name for {@code @RequestAttribute}. */
    public static final String ATTRIBUTE = "at.ac.meduniwien.ophthalmology.libreclinica.web.UploaderSources.SOURCE";

    private UploaderSources() {}

    /** The source the request was made for, or null when it carries none. */
    public static IngestSourceService.Source of(HttpServletRequest request) {
        if (request == null) return null;
        return request.getAttribute(ATTRIBUTE) instanceof IngestSourceService.Source s ? s : null;
    }

    /** Called by {@link UploaderTokenFilter} once a token has been accepted. */
    static void bind(HttpServletRequest request, IngestSourceService.Source source) {
        request.setAttribute(ATTRIBUTE, source);
    }

    /** Called by {@link UploaderTokenFilter} before it decides, so nothing stale survives. */
    static void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }
}
